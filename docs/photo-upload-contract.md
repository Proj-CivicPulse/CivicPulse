# Photo Upload — the contract, decided before the code

> **STATUS: SHIPPED 2026-10-25.** Built as specified below, with the open
> points of §7 decided and one deviation in §5, both marked **(as built)**.
> Storage: any S3-compatible bucket — a Railway Bucket in production,
> SeaweedFS locally. With no bucket configured the feature is off: the
> endpoints answer 503 `PHOTO_UPLOAD_UNAVAILABLE` and the confirmation screen
> says photo upload is unavailable.
>
> The caller-supplied `photoUrl` hole described in §8 stays **closed on both
> write paths** — `POST /complaints` does not accept the field, and
> `/internal/ingest` does not carry an upstream one into the column.

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
| Anonymous submitter | Request an intent and upload, for a complaint created in the same session. **(as built)** The proof is the `photoUploadToken` returned once on the create response — an HMAC over the complaint id and a 2-hour expiry — not the reference number: reference numbers are sequential, so the next one is guessable and anyone could attach a photo to a stranger's report |
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

## 7. The open points — decided 2026-10-25 (as built)

- **Provider: any S3-compatible bucket**, through the AWS SDK's S3 client and
  presigner (`S3PhotoStorage`). Production: a **Railway Bucket** (private,
  S3 API, presigned PUT/GET/HEAD; no lifecycle rules, hence the sweep in §4).
  Locally: a **SeaweedFS** container on :8333. Configured by
  `PHOTO_STORAGE_*` on backend-spring and `VITE_PHOTO_STORAGE_ORIGIN` on the
  frontend, which adds that one origin to the CSP's `connect-src` and
  `img-src` so the browser may PUT to and show from the bucket.
- **EXIF: stripped on confirm, by re-encoding.** The server decodes the upload
  (that is also the real type check) and writes a fresh JPEG of at most
  2048 px on its long edge: GPS, device id and every other metadata block are
  gone. The raw upload is then deleted. The declared canvas is checked from
  the header **before** decoding (≤ 50 MP), so a small file cannot ask for
  gigabytes of heap.
- **Thumbnails: generated on confirm**, 320 px, stored beside the photo
  (`photos/{complaintId}/{uuid}-thumb.jpg`). The officer's panel shows the
  thumbnail and links to the full image.
- **Column (§4): renamed** `photo_url` → `photo_object_key` (V26), with a
  CHECK that it starts `photos/`. The API exposes only `hasPhoto`.

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
key.

**The ingestion path was missed, and is now closed too.** The first version of
this section claimed "nothing can now put a value there from outside", and that
was wrong: `IngestionRecordProcessor` still copied `ExternalComplaint.photoUrl`
straight into the column, length-checked and otherwise untouched. The public
path had been closed and the internal one left open, which is the harder of the
two to notice precisely because it is called "internal".

A trusted feed operator is not a trusted feed. The URL in an imported record was
written by whoever filed the report upstream, and it was heading for the same
officer's dashboard with the same consequences — a tracking pixel reporting a
city employee's IP, arbitrary third-party imagery, and an SSRF target the moment
anything server-side fetched it. `IngestionValidator.Validated` no longer carries
the field and the processor no longer sets it.

The field is still **accepted** on the ingest DTO and preserved verbatim in
`ingestion_records.raw`. That is deliberate: a feed that sends one should not
have its record rejected, and the URL is useful evidence when reconciling against
the source. It is evidence there, not a target. Its length is no longer validated
either — refusing a usable report over the length of a field we discard would be
a rejection with no reason behind it.

**Why it cost nothing.** No client ever sent it, no feed is configured, and
`photo_url` is NULL on all 167 production complaints. The submit form has always
said photo upload is unavailable, so removing the field broke no caller.

**What still holds.** Nothing renders a stored value directly: the column holds
an object key the server wrote (`photo_object_key`, V26), and the browser only
ever sees a signed URL minted per request. Guard that in review.
