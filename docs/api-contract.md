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
| `RATE_LIMITED` | 429 | Node: general limiter (100 req / 15 min / IP). |
| `NOT_IMPLEMENTED` | 501 | Node: Phase 2/6/7 stub. |
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

## Spring → Node

### `POST /complaints/{id}/process` — Phase 2
Triggered after a complaint is created. Node generates the embedding,
pre-filters candidates (ward + category + active status), runs similarity,
and calls back to Spring with its decision.

```jsonc
// request: none (the id in the path is the whole input)
// 202
{ "complaintId": "123", "incidentId": "45", "created": false, "similarity": 0.87 }
```
Currently returns **501 `NOT_IMPLEMENTED`**.

---

## Node → Spring

All under `/internal/*`. Not reachable from the browser.

### `POST /internal/incidents/attach` — Phase 2
Node decides the match; **Spring performs the write.** In one transaction
Spring creates or loads the incident, sets `complaints.incident_id`, updates
`complaint_count`, and recomputes `priority_score` + `priority_reasons`.

```jsonc
// request — incidentId null means "start a new incident"
{ "complaintId": "123", "incidentId": "45", "similarity": 0.87 }
// 200
{ "incidentId": "45", "created": false, "priorityScore": 7.4 }
```

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
  "userId": "1",              // null — submission is open/anonymous
  "wardId": "3",
  "incidentId": "45",         // null until matched
  "departmentId": "2",        // null until triaged
  "title": "Large pothole",   // optional
  "description": "…",
  "category": "pothole",
  "lat": 12.9716,
  "long": 77.5946,
  "status": "open",           // open | in_progress | resolved | closed
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
  "priorityScore": 7.4,
  "priorityReasons": ["12 complaints in 5 days", "3 new in last 24h"],
  "status": "open",
  "complaintCount": 12,
  "lat": 12.9716,
  "long": 77.5946,
  "createdAt": "…",
  "updatedAt": "…"
}
```

> ⚠️ `frontend/src/services/incident.service.ts` does not yet model
> `title`, `summary`, `complaintCount`, `lat`/`long`. Align it when the
> first real incident endpoint lands.

---

## Open questions

- [ ] **Auth between services.** Node→Spring `/internal/*` is currently
      unauthenticated and only bound to localhost. Before anything is
      deployed: shared internal secret header, or a service JWT? Spring must
      also reject `/internal/*` from the browser.
- [ ] **Timeout/retry for Spring→Node.** A slow or dead Node currently
      stalls complaint submission (`AppConfig.restClient` has no timeouts —
      there is a TODO on the bean). Decide the budget and the fallback
      before Phase 2 ships.
- [ ] **Refresh-token revocation.** Tokens are stateless, so logout cannot
      invalidate a copied refresh token before it expires. A
      `refresh_tokens` table (jti + revoked_at) is the fix if the team wants
      real logout-everywhere.
- [ ] **Photo upload.** Direct-to-storage (client sends `photoUrl`, what the
      frontend scaffold assumes) vs. a multipart endpoint on Spring.

_Living document — update it in the same PR as any cross-service change._
