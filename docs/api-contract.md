# API Contract — Spring Boot ⇄ Node.js ⇄ Frontend

Single source of truth for how the services talk to each other and to the
browser. Update this **before** changing any endpoint another service or the
frontend depends on — treat it as a contract, not documentation-after-the-fact.

Ownership is settled in [`service-boundaries.md`](service-boundaries.md):
**Spring is the single writer and schema owner; Node is the intelligence
layer.**

---

## Conventions

| | |
|---|---|
| Base URL (Spring), local | `http://localhost:8080` |
| Base URL (Node), local | `http://localhost:3001` |
| Content type | `application/json` unless noted |
| Route prefixes | **None in either backend.** The frontend calls `/api/core/*` (Spring) and `/api/ai/*` (Node); the Vite dev proxy — and the production reverse proxy — strip that prefix before forwarding. Backend routes are prefix-free (`/health`, never `/api/ai/health`). |
| Auth (browser → Spring) | httpOnly cookies, `SameSite=Lax` (below) |
| Auth (Node → Spring, `/internal/*`) | **Open** — see Open questions |
| IDs on the wire | **Strings**, always — the frontend types every id as `string`, and it survives a later move to UUIDs |
| Enums on the wire | **lowercase** (`"citizen"`, `"open"`) — Java enums are uppercase internally and are converted at the DTO boundary |
| Timestamps | **ISO-8601 UTC with an explicit trailing `Z`**, always. Columns are zoneless `TIMESTAMP(6)`, so the JVM default zone is pinned to UTC at startup and `Wire.timestamp()` stamps the offset. Without the `Z` a browser parses the value as local time and every timestamp shifts by the viewer offset. |

### Error format

Every error from **both** backends, without exception:

```json
{ "error": { "code": "UPPER_SNAKE_CASE", "message": "human readable" } }
```

`frontend/src/services/api.ts` reads `error.message`; a flat body silently
degrades to a generic "Request failed" there.

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Body failed validation. Message names the fields and rules, never echoes values. |
| `INVALID_JSON` | 400 | Body was not parseable JSON. |
| `UNAUTHORIZED` | 401 | No session, or it is no longer usable. |
| `INVALID_CREDENTIALS` | 401 | Login failed. Identical for unknown email and wrong password — distinguishing them enables account enumeration. |
| `FORBIDDEN` | 403 | Authenticated, wrong role. |
| `NOT_FOUND` | 404 | No such resource or route. |
| `METHOD_NOT_ALLOWED` | 405 | |
| `EMAIL_ALREADY_EXISTS` | 409 | Registration conflict. |
| `PAYLOAD_TOO_LARGE` | 413 | Node: body over 100 kb. |
| `RATE_LIMITED` | 429 | Node: general limiter (100 req / 15 min / IP). **`GET /health` and `POST /complaints/{id}/process` are exempt** — the latter is service-to-service, gated by `X-Internal-Token`; leaving it inside the IP budget lets a reconcile backlog exhaust the budget and hand Spring 429s it reads as an outage. |
| `NOT_IMPLEMENTED` | 501 | Node: Phase 6/7 stub. |
| `EMBEDDING_UPSTREAM_ERROR` / `CORE_UPSTREAM_ERROR` | 502 | Node: the embedding provider, or backend-spring on an `/internal` call, failed or was unreachable. Seen only on `POST /complaints/{id}/process`; it is what trips Spring's matching circuit breaker. |
| `INTERNAL_ERROR` | 500 | Deliberately opaque. The real cause is logged server-side only. |

**Never** put stack traces, SQL, or internal detail in `message` — in either
service, in any environment.

> **Note:** an *unauthenticated* request to an unknown path on Spring returns
> **401**, not 404. Spring is default-deny (`anyRequest().authenticated()`),
> so any newly added endpoint is protected until someone opens it
> deliberately. Authenticated callers get a normal 404.

---

## Browser → Spring: session

Tokens travel as **httpOnly cookies**, never in a response body.
`frontend/src/stores/auth.store.ts` deliberately keeps no token in
JS-reachable storage, so an XSS bug cannot exfiltrate a session.
`frontend/src/services/api.ts` sends `credentials: 'include'` on every call.

| Cookie | Contains | Lifetime |
|---|---|---|
| `cp_access_token` | access JWT (`typ: "access"`) | 15 min |
| `cp_refresh_token` | refresh JWT (`typ: "refresh"`) | 7 days |

Both: `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` when `COOKIE_SECURE=true`.

- The `typ` claim is enforced on every parse, so a refresh token cannot be
  replayed as an access token.
- `Path=/` is deliberate: the browser matches Path against the URL *it* sees
  (`/api/core/...`), not Spring's own route.
- CSRF protection comes from `SameSite=Lax` — a cross-site request never
  carries these cookies. Spring's CSRF tokens are disabled **on that basis**;
  if any cookie ever becomes `SameSite=None`, CSRF must be re-enabled.

### `POST /auth/register`
Always creates a **citizen**. Officer accounts are provisioned deliberately
(see `backend-spring/README.md`).

```jsonc
// request
{ "name": "Asha", "email": "asha@example.com", "password": "Str0ng!Pass1" }
// 201 + Set-Cookie ×2
{ "user": { "id": "1", "name": "Asha", "role": "citizen" } }
```
Password: ≥8 chars, with lowercase, uppercase, digit, and special character.
409 `EMAIL_ALREADY_EXISTS` if taken.

### `POST /auth/login`
```jsonc
// request
{ "email": "asha@example.com", "password": "Str0ng!Pass1" }
// 200 + Set-Cookie ×2
{ "user": { "id": "1", "name": "Asha", "role": "officer" } }
```

### `GET /auth/me`
Session restore on every app boot. `200 { "id", "name", "role" }`, or
**401** when unauthenticated — which the frontend treats as "logged out",
not as an error.

### `POST /auth/refresh`
Exchanges a valid refresh cookie for a **rotated** pair. Public by design —
the access token is expected to be expired here. `200 { "user": … }`, or 401.

### `POST /auth/logout`
`204`, clears both cookies. Idempotent. Tokens are stateless, so a copied
token stays valid until it expires — see Open questions.

---

## Browser → either backend: health

`GET /health` on **both** services. Identical shape, consumed by
`frontend/src/services/health.service.ts`:

```jsonc
200 { "status": "ok" }      // reachable AND its database answered SELECT 1
503 { "status": "error" }   // anything else
```

Never a hardcoded 200. Both bound the probe (~3 s) so an unreachable
database fails fast instead of hanging the request.

---

## Browser → Spring: reference data, complaints, incidents, dashboard

Every path below is prefix-free on the backend; the browser calls
`/api/core/...` and the proxy strips it.

### Reference data — public

| Method | Path | Access | Returns |
|---|---|---|---|
| GET | `/wards` | public | `Ward[]` |
| GET | `/wards/{id}` | public | `Ward`, 404 if unknown |
| GET | `/wards/resolve?lat=&long=` | public | `Ward` |
| GET | `/wards/summary` | public | `WardSummary[]` |
| GET | `/departments` | authenticated | `{id, name}[]` |
| GET | `/departments/{id}` | authenticated | `{id, name}` |

`GET /wards` is public because a resident submitting anonymously needs the
ward picker when resolution fails. Only `GET` is open — a future write
endpoint under `/wards` stays default-deny.

#### `GET /wards/resolve`
Derives the ward from a coordinate pair, so the submit form can show that the
system already knows the location rather than asking for it.

```jsonc
// 200
{ "id": "3", "code": "17", "name": "Ward 17", "zone": "South", "lat": 12.925, "long": 77.5938 }
// 404 — a real answer, not a failure
{ "error": { "code": "NOT_FOUND", "message": "No ward covers that location" } }
```

404 beyond `app.ward-resolution-max-km` (default 25 km). Returning the nearest
ward regardless would file an out-of-city report into whichever ward happened to
be least far away, corrupting both the dashboard and the Phase 2 candidate
pre-filter, invisibly. Callers fall back to the `GET /wards` picker.

Implementation is behind a `WardResolver` seam — nearest seeded centroid today,
point-in-polygon once real boundaries are imported. Swapping it is a config
change (`WARD_RESOLVER`) and touches no caller.

#### `GET /wards/summary`
Feeds the public landing strip. **Counts, never rows.**

```jsonc
// 200, Cache-Control: public, max-age=60
[{ "wardId": "3", "wardName": "Ward 17", "openIncidentCount": 7 }]
```

It lives under `/wards` — a prefix that is already entirely public — rather
than carving a `permitAll` hole inside `/dashboard/**` or `/incidents/**`.
A hole inside an officer-only prefix flips the default for everything added
there later from "officer-only" to "whatever the author remembered". **A prefix
should have one posture, not exceptions.**

**Never add to this shape:** incident titles or summaries (LLM prose over
residents complaint text), coordinates (a single-complaint incident centroid
*is* that complaint address), a category breakdown (at count 1, "Ward 17 has 1
open <sensitive category> incident" approaches identifying a person), or
priority scores (leaks the operational model). What it does disclose is an
aggregate that identifies nobody and cannot be differenced into individual
records.

### Complaints

| Method | Path | Access | Notes |
|---|---|---|---|
| POST | `/complaints` | **public** | 201 + `Location`. Attributes to the caller when signed in. |
| GET | `/complaints` | **officer** | Full text of every complaint — not `authenticated` |
| GET | `/complaints/mine` | **citizen** | Newest first |
| GET | `/complaints/{id}` | authenticated | Officer reads any; citizen reads only their own |
| PATCH | `/complaints/{id}` | **officer** | `{ status?, departmentId? }`, at least one required |

`POST /complaints` accepts an **optional** `wardId`. When absent the server
derives it from `lat`/`long`; when neither yields a ward the request is
rejected rather than guessed at. The reference number is allocated inside the
creating transaction, so a rollback returns the number instead of burning it.

`GET /complaints/{id}` returns **404, not 403**, when a citizen asks for someone
else complaint. A 403 would confirm the id exists and turn sequential ids into
an enumeration oracle.

### Incidents — officer only

| Method | Path | Notes |
|---|---|---|
| GET | `/incidents` | `?wardId&category&status&minPriority`, sorted by priority desc then recency |
| GET | `/incidents/{id}` | |
| GET | `/incidents/{id}/complaints` | 404 if the incident is unknown, so an empty list is unambiguous |
| PATCH | `/incidents/{id}` | `{ status?, departmentId?, title?, summary? }`, at least one required |

`PATCH` does **not** recompute priority. Status is an officer judgement about
handling, not an input to the model — only membership changes move a score.

An unknown `status` returns 400 naming the allowed values; `minPriority`
outside 0–10 returns 400.

### Dashboard — officer only

| Method | Path |
|---|---|
| GET | `/dashboard/summary` |
| GET | `/dashboard/wards/{wardId}/summary` |

---

## Spring → Node

### `POST /complaints/{id}/process` — Phase 2

Triggered **asynchronously** after a complaint commits (`AFTER_COMMIT`, on a
bounded executor — the citizen's response never waits on it), and again by
Spring's reconcile sweep. Node claims the complaint (`PROCESSING`), generates
the embedding, pre-filters candidates (ward + category + active status), runs
single-linkage similarity, and calls `POST /internal/incidents/attach` back.

**Requires the `X-Internal-Token` header** (same secret and constant-time
compare as Spring's `/internal/*`; blank `INTERNAL_TOKEN` denies the route).

```jsonc
// request: none (the id in the path is the whole input)
// query: ?force=true   also claims a MATCHED complaint / a stale PROCESSING one
//        ?reembed=true  regenerate the vector even if one is stored

// 202 — Node processed it and called attach back
{ "complaintId": "123", "incidentId": "45", "created": false, "similarity": 0.87, "matcher": "semantic" }

// 200 — the CAS claim failed: another run owns it, or it is terminal and no ?force
{ "complaintId": "123", "skipped": true }
```

`5xx` from this route (embedding provider down, backend-spring unreachable) is
what trips Spring's circuit breaker → naive fallback. A read timeout does not,
on its own — Spring re-checks `matching_status` and defers if Node holds the
claim. See `service-boundaries.md` decision 2.

---

## Node → Spring

All under `/internal/*`. Not reachable from the browser.

### `POST /internal/incidents/attach` — Phase 2
Node decides the match; **Spring performs the write.** In one transaction
Spring creates or loads the incident, sets `complaints.incident_id`,
`matching_status`, `matched_at`, `complaint_count`, recomputes
`priority_score` + `priority_reasons`, and writes one `incident_match_log` row.
If the complaint was already on a different incident (a reconcile move) the old
one is recomputed, and deleted if it is now empty.

```jsonc
// request — incidentId null means "start a new incident". Everything below
// incidentId is score context for the incident_match_log row (Phase 8); all
// optional, and the naive fallback sends none of it.
{
  "complaintId": "123",
  "incidentId": "45",
  "topSimilarity": 0.87,
  "topSiblingComplaintId": "4821",
  "candidateCount": 3,
  "threshold": 0.75,
  "model": "gemini-embedding-001",
  "embeddingDim": 1536
}
// 200
{ "incidentId": "45", "created": false, "priorityScore": 7.3, "matchingStatus": "matched" }
```

A `NAIVE` decision that arrives after a semantic result has already landed
(`matching_status` is `matched` or `processing`) is a no-op — Spring returns the
current state and writes nothing.

**Requires the `X-Internal-Token` header.** The value comes from
`INTERNAL_TOKEN`; Spring compares it in constant time and **denies every
`/internal/*` request when the secret is blank** — an unset secret breaks the
integration rather than opening it.

Named `/incidents/attach` rather than the sub-collection form
`/internal/incidents/{id}/complaints` that `endpoints.md` originally listed,
because `incidentId` may legitimately be null ("start a new incident") and a
null cannot occupy a path segment.

### `POST /internal/incidents/{id}/recompute` — Phase 2

Re-derives an incident's membership-derived fields — `complaintCount`, centroid,
`address`, `priorityScore`, `priorityReasons` — **without changing membership**.
Returns the `Incident` shape below; 404 if the incident is unknown.

```jsonc
// request: none (the id in the path is the whole input)
// 200 -> the recomputed Incident
```

Deliberately separate from `/incidents/attach`. attach records a matching
*decision*: it stamps `matching_status` and appends an `incident_match_log` row.
Re-deriving a score is neither of those, and routing it through attach would
fabricate "semantic match" events that never happened and corrupt the dataset
Phase 8 measures precision/recall on.

It exists because the **age term of the priority formula is time-dependent**, so
a stored score drifts with no writes at all (see `PriorityService`). Consumed
today by `backend-spring/scripts/seed-dev-data.mjs --rescore`; it is also the
natural hook for the scheduled priority refresh that is still a follow-up.

### `GET /internal/complaints/{id}` — Phase 2
Full complaint context for embedding generation and Copilot grounding.
Returns the `Complaint` shape below.

### `GET /internal/wards/{id}` — Phase 2
Ward metadata for candidate filtering.

---

## Browser → Node

### `POST /copilot/query` — Phase 6
```jsonc
// request
{ "officer_id": "7", "query": "what's urgent in Ward 17", "ward_id": "3" }
// 200
{ "answer": "…", "sources": ["incident:45", "incident:52"] }
```
Currently **501**. Body validation is already enforced, so a malformed
request gets 400 `VALIDATION_ERROR` before the 501.

### `GET /hotspots/predict` — Phase 7, conditional
Currently **501**.

---

## Shared data shapes

Wire shapes, not database rows: ids are strings and enums are lowercase.
Keep in sync with `backend-spring/src/main/resources/db/migration/`.

### `Complaint`
```jsonc
{
  "id": "123",
  "referenceNo": "CP-2026-W17-00412",  // the resident receipt; unique, gap-free
  "userId": "1",              // null — submission is open/anonymous
  "wardId": "3",
  "incidentId": "45",         // null until matched
  "incidentComplaintCount": 12, // null until matched; see note below
  "departmentId": "2",        // null until triaged
  "title": "Large pothole",   // optional
  "description": "…",
  "category": "pothole",
  "lat": 12.9716,
  "long": 77.5946,
  "status": "open",           // open | in_progress | resolved | closed
  "matchingStatus": "pending", // pending | processing | matched | degraded — Phase 2 pipeline state
  "address": "4th Cross, Jayanagar, Bengaluru",  // null when geocoding is off
  "photoUrl": "https://…",    // optional
  "createdAt": "2026-08-29T10:15:00Z",
  "updatedAt": "2026-08-29T10:15:00Z"
}
```

### `Incident`
```jsonc
{
  "id": "45",
  "wardId": "3",
  "departmentId": "2",
  "title": "Potholes on MG Road",
  "summary": "…",
  "category": "pothole",
  "priorityScore": 7.3,
  "priorityBand": "high",     // low | medium | high | critical
  "priorityReasons": ["12 complaints reported", "3 new complaints in the last 24 hours"],
  "status": "open",
  "address": "MG Road, Bengaluru",  // centroid address; null when geocoding is off
  "complaintCount": 12,
  "lat": 12.9716,
  "long": 77.5946,
  "aiRecommendation": null,   // written by Node in Phase 6; null until then
  "createdAt": "…",
  "updatedAt": "…"
}
```

**`priorityBand` is computed server-side** from `priorityScore`:

| Band | Score |
|---|---|
| `low` | `< 3.0` |
| `medium` | `3.0 – 5.5` |
| `high` | `5.5 – 7.5` |
| `critical` | `>= 7.5` |

The thresholds are part of the prioritisation model, and the model lives in
Spring (`service-boundaries.md` decision 1). Three consumers need the band —
the dashboard, the Leaflet marker colour, and Node Copilot — and three private
copies of four numbers is three chances to disagree. The raw score ships
alongside it so clients can still sort finely.

**`priorityReasons` may be empty**, and clients must render that as an explicit
"No justification recorded" rather than hiding the section. In practice
`PriorityService` always emits at least one reason (a lone report gets
"Single report — awaiting corroboration"), so an empty array means something
was written outside the service.

**`incidentComplaintCount` on `Complaint`** carries the incident total so the
citizen My-reports screen can say "Grouped with N other reports".
`GET /incidents/{id}` is officer-only, so a citizen has no other route to that
number; it is an aggregate and discloses nothing else about the incident.

**`matchingStatus` on `Complaint`** is the Phase 2 pipeline state:

| Value | Meaning |
|---|---|
| `pending` | created, not yet processed (or a failed run was released) |
| `processing` | a backend-node run holds the claim right now |
| `matched` | the semantic matcher assigned it — to a new *or* an existing incident |
| `degraded` | the naive fallback assigned it while Node was unreachable; the reconcile sweep will upgrade it |

A freshly submitted complaint is always `pending` in the `POST /complaints`
response — matching runs after the response is sent. The officer dashboard uses
this to show which incidents are still settling; a citizen-facing rendering is a
later follow-up.

### `Ward`
```jsonc
{
  "id": "3",
  "code": "17",               // stable ward number used in reference numbers
  "name": "Ward 17",
  "zone": "South",            // nullable
  "lat": 12.9250,             // centroid; nullable
  "long": 77.5938
}
```

Note `id` and `code` differ: the seeded "Ward 17" has `id: "3"`. Reference
numbers use `code`, so a resident receipt reads `W17` and matches what the UI
shows them. `name` is a mutable display label and must never feed a printed
identifier.

### `WardSummary`
```jsonc
{ "wardId": "3", "wardName": "Ward 17", "openIncidentCount": 7 }
```

**Counts, never rows.** This shape is served unauthenticated to the public
landing page. Incident titles, coordinates, category breakdowns, and priority
scores must never be added to it — see the note on `GET /wards/summary`.

---

## Third-party services

### Reverse geocoding — server-side only

Coordinates get a street address so a resident recognises their own report and
an officer knows where to send a crew. Called from **Spring, never the browser**.

That placement is the whole design:

- the API key stays **secret and IP-restrictable**, rather than a public
  referrer-scoped key shipped in JavaScript;
- the frontend's `default-src 'self'` CSP is untouched — a browser-side Google
  call would have required opening `script-src` and `connect-src`;
- results are **cached in `geocode_cache`**, keyed by coordinates rounded to
  ~11 m, so cost tracks distinct **locations** rather than complaint volume.

That last point is why this is the paid dependency the project takes on and
Google Maps tiles are not:

| | Bills per | Bounded by | Cacheable |
|---|---|---|---|
| Map tiles | page view | nothing — every refresh is a new load | No |
| Geocoding | new location | actual civic activity | Yes, forever |

A street name does not change, so a location is paid for once and reused for
every later report there. Negative results are cached too — a point with no
address will not acquire one next week, and paying to rediscover that is the
worst kind of spend.

**Failure is never fatal.** Provider down, over quota, misconfigured key, or
disabled entirely — all produce `address: null` and a report that saves
normally. Geocoding is an enrichment, and nothing user-facing may depend on a
third party being up.

Default is `GEOCODING_PROVIDER=none`, so a fresh clone runs with no key and no
billing account.

### Embeddings — server-side, backend-node only

Complaint text → a vector, so pgvector can measure semantic similarity between
complaints for incident matching (Phase 2).

| | |
|---|---|
| Provider | Google Gemini, `gemini-embedding-001` (Generative Language API) |
| Endpoint | `POST .../v1beta/models/gemini-embedding-001:embedContent?key=…` |
| Key | `EMBEDDING_API_KEY` — an **AI Studio key**, distinct from `GOOGLE_GEOCODING_API_KEY` |
| Dimensions | **1536** (`outputDimensionality`); must equal `complaints.embedding vector(1536)` |
| Task type | `SEMANTIC_SIMILARITY` |
| Normalisation | L2-normalised **in Node** — Gemini only pre-normalises the full 3072-dim output |

**Multilingual on purpose** — complaint text in this deployment mixes English,
Hindi, and Kannada, and `gemini-embedding-001` handles that in one model.

Unlike geocoding, this is **not cacheable per location** — every distinct
complaint is a new call. The stored vector is reused (a reprocess without
`?reembed=true` skips the call), and the ward + category + status pre-filter
keeps the *similarity* maths cheap, but the embedding call itself scales with
complaint volume. Every similarity score is logged to `incident_match_log` for
the Phase 8 threshold tuning.

**Failure is not fatal to submission** (the trigger is async) but it does block
that complaint's matching: Node returns `502`, Spring's breaker counts it, the
naive fallback runs, and the reconcile sweep retries once the provider is back.

---

## Open questions

- [x] **Auth between services.** Resolved: `/internal/*` requires the
      `X-Internal-Token` header, compared in constant time, failing closed when
      `INTERNAL_TOKEN` is blank. A browser cannot set a custom header
      cross-origin without a preflight CORS refuses, and the token is never in
      shipped JavaScript. **Still open:** rotation policy.
- [x] **Timeout/retry for Spring→Node.** Resolved in Phase 2: the trigger is
      async (`AFTER_COMMIT`, bounded executor), so it never stalls submission.
      `matchingRestClient` has a 2.5 s read timeout; a `MatchingCircuitBreaker`
      opens after 3 consecutive connection failures; the fallback is
      `NaiveIncidentGrouper` (`matching_status = degraded`); `MatchReconciliationJob`
      re-runs the real pipeline once Node recovers. See `service-boundaries.md`
      decision 2.
- [ ] **Refresh-token revocation.** Tokens are stateless, so logout cannot
      invalidate a copied refresh token before it expires. A
      `refresh_tokens` table (jti + revoked_at) is the fix if the team wants
      real logout-everywhere.
- [ ] **Photo upload.** Direct-to-storage (client sends `photoUrl`, what the
      frontend scaffold assumes) vs. a multipart endpoint on Spring.

_Living document — update it in the same PR as any cross-service change._
