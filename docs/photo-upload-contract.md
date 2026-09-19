# Photo Upload — the contract, decided before the code

> **STATUS: DEFERRED.** The contract below is decided and approved. The
> implementation is deliberately not started and is **out of scope for this
> release**. The submit form says photo upload is unavailable, and that copy
> stays until this ships.
>
> The caller-supplied `photoUrl` hole described in §8 is **now closed** —
> `POST /complaints` no longer accepts the field at all.

ISSUE-16's instruction was to **define the media contract before implementing or
exposing the feature as complete**. This is that definition.

Read this before writing any of it.

---

## 1. The decision that was open

`endpoints.md` has carried this since Phase 1:

> Direct-to-storage upload (client sends a `photoUrl` string) vs. a multipart
> upload endpoint on Spring.

**Decided: direct-to-storage, with a server-issued upload URL.** Neither of the
two options as originally framed is right, and the reason matters.

- **Multipart to Spring** makes every photo pass through the application. A
  4 MB image on an Indian mobile connection holds a request thread for the
  length of the upload; a hundred concurrent reports do that a hundred times.
  The service is a small container behind a platform with request timeouts, and
  image bytes are the one payload it has no reason to touch.
- **Client sends an arbitrary `photoUrl`** — which the DTO accepted until this
  was closed (§8) — is worse. It let any
  caller store any URL against a complaint. That URL is then rendered in an
  officer's dashboard. It is an SSRF vector if the server ever fetches it, a
  tracking pixel if it does not, and a way to put arbitrary third-party content
  in front of a city employee either way.

So: the client asks the server for permission to upload, uploads directly to
object storage, and the server records only a key it issued itself.

---

## 2. The flow

```
1. POST /complaints/{id}/photo-intent      (Spring)
       -> { uploadUrl, objectKey, expiresAt, maxBytes, contentType }
2. PUT  <uploadUrl>                         (object storage, direct)
3. POST /complaints/{id}/photo              (Spring)
       { objectKey }
       -> Spring HEADs the object, validates it, sets complaints.photo_url
```

**Why three steps rather than two.** Step 3 exists because step 2 happens where
the server cannot see it. Without a confirm step, a client that abandons the
upload leaves a complaint pointing at an object that does not exist, and one
that uploads something other than what it declared is never caught. Step 3 is
where the server checks the object is there, is the size and type it permitted,
and only then writes anything to the complaint.

**`photo_url` is no longer caller-supplied.** `CreateComplaintRequest.photoUrl`
has already been removed (§8), so the confirm step above will be the only way a
value reaches that column. Do not reintroduce the field alongside it.

---

## 3. Validation rules

Enforced at step 1 (what the signed URL permits) **and** re-checked at step 3
(what actually arrived). Both, because a signed URL constrains what storage will
accept and tells you nothing about what the client did with it.

| Rule | Value | Why |
|---|---|---|
| Content types | `image/jpeg`, `image/png`, `image/webp` | What a phone camera produces. No SVG — it is a script container, not an image. No HEIC until something can transcode it. |
| Max size | 8 MB | Comfortably above a modern phone photo, below anything that is a payload rather than a picture. |
| Max per complaint | 1 | The schema has a single `photo_url`. More than one is a different feature and a different column. |
| Intent URL lifetime | 10 minutes | Long enough for a slow upload on a bad connection, short enough that a leaked URL is not a standing write grant. |
| Object key | server-generated UUID, never the client's filename | A client-supplied name is a path-traversal and overwrite vector, and leaks whatever the file was called on someone's phone. |
| Dimensions | reject below 64x64 | A 1x1 image is a tracking pixel, not a report. |

An upload that fails any check leaves the complaint **unchanged**, with its
`photo_url` still null. A report without a photo is still a perfectly good
report — the same principle the address field already follows.

---

## 4. Storage and lifecycle

- **Bucket is private.** No public read. Nothing in it is served directly to a
  browser.
- **Reads go through a short-lived signed GET URL** minted by Spring when an
  officer opens the complaint, not stored in the database. A permanent public
  URL in `photo_url` would make every photo of every resident's property
  world-readable to anyone who has ever seen one link.
- **`complaints.photo_url` stores the OBJECT KEY**, not a URL. The column name
  is now wrong and should be migrated to `photo_object_key`. A stored URL bakes
  in a hostname and a signing scheme that will both change.
- **Deletion.** A complaint's photo is deleted with the complaint. There is no
  separate delete endpoint for citizens: the photo is evidence attached to a
  report, and letting a reporter remove it after an officer has acted on it
  creates a dispute with no record.
- **Orphans.** Objects whose intent was issued but never confirmed are swept
  after 24 hours by a scheduled job. Without it the bucket accumulates every
  abandoned upload forever.

---

## 5. Access rules

| Actor | Can |
|---|---|
| Anonymous submitter | Request an intent and upload, for a complaint created in the same session (the reference number is the proof) |
| Citizen | The same, for their own complaints only |
| Officer | Read any photo, via a signed GET |
| Anyone else | Nothing |

Photo access follows the complaint's own access rules exactly — a citizen who
cannot read a complaint cannot read its photo. The 404-not-403 rule applies
here too: a photo endpoint that distinguishes "not yours" from "does not exist"
is an enumeration oracle over complaint ids.

---

## 6. Frontend behaviour on failure

Specified, because "the upload failed" is the common case on a mobile
connection and an unspecified one produces a form that silently loses a report.

- The photo field is **never a submit gate.** The complaint is created first,
  and the photo is attached afterwards. A resident who cannot upload has still
  filed their report.
- An upload that fails is retryable **from the confirmation screen**, which
  already shows the reference number.
- An expired intent re-requests one transparently; it is not an error the
  resident should ever read about.
- The submit form's current copy ("Photo upload isn't available yet") is what
  changes when this ships, and not before.

---

## 7. What is NOT decided

- **Which provider.** The flow above is provider-neutral and works with S3, R2,
  GCS or Supabase Storage. Nothing should be written until one is chosen,
  because the intent endpoint's shape depends on its signing API.
- **Whether images are stripped of EXIF.** A phone photo carries GPS
  coordinates and a device identifier. Since the complaint already carries
  coordinates deliberately, the EXIF copy adds no function and some risk. The
  likely answer is "strip on confirm", which needs an image library and belongs
  in the implementation decision.
- **Thumbnails.** The officer dashboard will want them; whether they are
  generated on confirm or on read is a performance question with no data yet.

---

## 8. The caller-supplied `photoUrl` hole — CLOSED

**What it was.** `POST /complaints` accepted a `photoUrl` string and stored it
unchanged. Nothing validated it, and nothing had issued it. The first UI to
render it would have turned that into a tracking pixel reporting an officer's IP
to a third party, arbitrary imagery inside the dashboard, and — the moment any
server-side code fetched it for thumbnailing or EXIF stripping — an SSRF vector
pointed at the platform's internal network.

**What changed.** The field is gone from `CreateComplaintRequest`,
`ComplaintService.create` no longer copies it, and it is gone from the
frontend's `CreateComplaintInput`. `complaints.photo_url` remains in the schema
and on the response DTO — it is where §2's confirm step will write the object
key — but nothing can now put a value there from outside.

**Why it cost nothing.** No client ever sent it. The submit form has always said
photo upload is unavailable, so removing the field broke no caller.

**What still holds.** Nothing may render `photo_url` until §2 ships. That is the
property that kept this inert while the field was open, and it is still the one
to guard in review.
