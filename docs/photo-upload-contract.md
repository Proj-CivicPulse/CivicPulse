# Photo Upload — the contract, decided before the code

> **STATUS: DEFERRED.** The contract below is decided and approved. The
> implementation is deliberately not started and is **out of scope for this
> release**. The submit form says photo upload is unavailable, and that copy
> stays until this ships.
>
> **One thing here is a live defect, not a deferral.**
> `CreateComplaintRequest.photoUrl` is still accepted on the public
> `POST /complaints` and stored verbatim in `complaints.photo_url`. Any caller
> can therefore store any URL against a complaint. It is currently
> **unexploitable in practice** — nothing in the frontend renders `photoUrl`, so
> the value is inert — but it becomes live the moment any UI displays it.
> See §8.

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
- **Client sends an arbitrary `photoUrl`** — which is what the DTO accepts
  today — is worse, and is the reason this must not ship as-is. It lets any
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

**`photo_url` stops being caller-supplied.** `CreateComplaintRequest.photoUrl`
must be removed from the public API at the same time this ships. Leaving both
paths open leaves the hole this design exists to close.

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

## 8. The one live defect: caller-supplied `photoUrl`

**What it is.** `POST /complaints` accepts a `photoUrl` string and stores it
unchanged. Nothing validates it, and nothing issued it.

**Why it is not exploitable today.** No frontend code renders `photoUrl` — it
exists in `complaint.service.ts` as a type and nowhere else. A stored URL is
inert text. This was verified, not assumed:

```bash
grep -rn "photoUrl" frontend/src --include=*.tsx   # no render sites
```

**What makes it live.** The first UI that displays it. At that moment a
caller-supplied URL becomes: a tracking pixel that reports an officer's IP and
user-agent to a third party; arbitrary third-party imagery shown inside the
officer dashboard; and, if any server-side code ever fetches it (thumbnailing,
EXIF stripping, virus scanning — all plausible next steps), an SSRF vector
pointed at the platform's internal network.

**Decision.** Removing the field now is a public API change for a field nothing
uses, made under time pressure before a release. It is not removed in this
release. Instead:

- **It is a release-blocking prerequisite of the photo feature, not of this
  deploy.** `CreateComplaintRequest.photoUrl` and `ComplaintService`'s use of it
  must be deleted in the same change that adds the intent/confirm endpoints of
  §2. Shipping the upload flow while the old field remains open leaves the hole
  wide and adds a second way in.
- **Nothing may render `photo_url` until then.** That is the property keeping
  this inert, and it is the one to guard in review.

**If you want it closed sooner** it is a three-line change — drop the field from
the DTO, drop `.photoUrl(request.getPhotoUrl())` from `ComplaintService.create`,
drop it from the frontend's request type — and costs nothing, because no client
sends it.
