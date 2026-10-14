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
| GET | `/categories` | public | `Category[]` |
| GET | `/departments` | authenticated | `{id, name}[]` |
| GET | `/departments/{id}` | authenticated | `{id, name}` |

`GET /wards` is public because a resident submitting anonymously needs the
ward picker when resolution fails. Only `GET` is open — a future write
endpoint under `/wards` stays default-deny.

#### `GET /categories`
The canonical category registry. Public for the same reason `GET /wards` is:
the submit form renders the picker before anyone has signed in.

```jsonc
// 200, Cache-Control: public, max-age=3600
[
  { "code": "pothole", "name": "Pothole / road damage", "displayName": "Pothole or damaged road" },
  { "code": "garbage", "name": "Solid waste",           "displayName": "Garbage not collected" }
]
```

Active categories only, in submit-form order. **`code` is the only value a
client may submit**, and `displayName` is the only one it should render —
keeping them separate is what lets a label be reworded without invalidating
every stored complaint.

`POST /complaints` now **rejects a category the registry cannot resolve**
(`400 VALIDATION_ERROR`, message naming the accepted codes). That is a
deliberate change from the previous free-form `VARCHAR`: category is an
exact-equality key for the Phase 2 candidate pre-filter, the naive fallback and
the dashboard breakdown, so an unrecognised value files a report that no
aggregate will ever find. Aliases mean this is rarely hit — `Garbage`,
`solid waste` and `Uncollected Garbage` all resolve to `garbage` — but an
invented value is refused rather than stored.

**Aliases are deliberately not exposed.** They are an ingestion concern, and
publishing them would invite clients to submit one directly and then depend on
a mapping that exists to be revised.

#### `GET /wards/resolve`
Derives the ward from a coordinate pair, so the submit form can show that the
system already knows the location rather than asking for it.

```jsonc
// GET /wards/resolve?lat=12.9479&long=77.5057 → 200
{ "id": "538", "code": "48", "name": "Jnana Bharathi", "zone": null, "lat": 12.9478675, "long": 77.5057219 }
// 404 — a real answer, not a failure
{ "error": { "code": "NOT_FOUND", "message": "No ward covers that location" } }
```

Point-in-polygon against the 243 imported BBMP boundaries (`PostGisWardResolver`,
the default — see [ward-data.md](ward-data.md)). **404 when no active ward
contains the point**; there is no nearest-ward fallback. Returning the nearest
ward regardless would file an out-of-city report into whichever ward happened to
be least far away, corrupting both the dashboard and the Phase 2 candidate
pre-filter, invisibly. Callers fall back to the `GET /wards` picker.

Implementation is behind a `WardResolver` seam. `WARD_RESOLVER=centroid`
selects the old nearest-centroid resolver (404 beyond
`app.ward-resolution-max-km`, default 25 km) for a database without PostGIS;
production runs `postgis`.

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
| GET | `/complaints` | **officer** | Full text of every complaint — not `authenticated`. Paged `{ items, total, limit, offset }`, newest report first; see E4 |
| GET | `/complaints/mine` | **citizen** | Newest first |
| GET | `/complaints/{id}` | authenticated | Officer reads any; citizen reads only their own |
| PATCH | `/complaints/{id}` | **officer** | `{ status?, departmentId? }`, at least one required |

`POST /complaints` accepts an **optional** `wardId`. When absent the server
derives it from `lat`/`long`; when neither yields a ward the request is
rejected rather than guessed at. The reference number is allocated inside the
creating transaction, so a rollback returns the number instead of burning it.

`POST /complaints` does **not** accept `photoUrl`. It used to, and stored it
verbatim — which let any caller put an arbitrary URL on a complaint that an
officer's dashboard would later render. `photoUrl` remains on the RESPONSE
shape, because that column is where the server-issued upload flow in
`photo-upload-contract.md` will write an object key it minted itself.

`GET /complaints/{id}` returns **404, not 403**, when a citizen asks for someone
else complaint. A 403 would confirm the id exists and turn sequential ids into
an enumeration oracle.

### Incidents — officer only

| Method | Path | Notes |
|---|---|---|
| GET | `/incidents` | Paged `{ items, total, limit, offset }`. Repeatable `wardId`, `category`, `status`, `priorityBand`; `from`/`to` (member `reportedAt`), `q`, `sort`, `dir`, `limit`, `offset` — see E1 |
| GET | `/incidents/{id}` | |
| GET | `/incidents/{id}/complaints` | 404 if the incident is unknown, so an empty list is unambiguous |
| PATCH | `/incidents/{id}` | `{ status?, departmentId?, title?, summary? }`, at least one required |

`PATCH` does **not** recompute priority. Status is an officer judgement about
handling, not an input to the model — only membership changes move a score.

An unknown `status` returns 400 naming the allowed values; `minPriority`
outside 0–10 returns 400.

### Dashboard — officer only

| Method | Path | Returns |
|---|---|---|
| GET | `/dashboard/summary` | `DashboardSummary` |
| GET | `/dashboard/wards/{wardId}/summary` | `WardDashboardSummary`; 404 for an unknown ward |

No parameters, no pagination; every list inside is capped server-side.

#### `DashboardSummary`
```jsonc
// production values, 2026-10-13
{
  "totalComplaints": 167,
  "openComplaints": 166,       // complaints.status = OPEN only (not IN_PROGRESS)
  "resolvedComplaints": 0,     // complaints.status = RESOLVED only (not CLOSED)
  "totalIncidents": 31,
  "openIncidents": 31,         // incidents.status IN (OPEN, IN_PROGRESS) — "active"
  "highPriorityIncidentCount": 0, // active, priorityBand in (high, critical)
  "complaintsLast24h": 0,      // complaints REPORTED in the last 24 h (reported_at)
  "complaintsLast7d": 0,       // … last 7 days
  "topCategories": [{ "category": "pothole", "count": 59 }],  // ≤ 5, by complaint count, all statuses
  "wards": [WardSummary],      // every ACTIVE ward (243), same shape as GET /wards/summary
  "topIncidents": [Incident]   // ≤ 5 ACTIVE incidents, priorityScore desc, ties by id
}
```

Note the naming trap: `openComplaints` means status `OPEN` exactly, while
`openIncidents` means *active* (`OPEN` + `IN_PROGRESS`). New fields use the
Explorer vocabulary below (`activeIncidentCount`) rather than extending the
ambiguity; these two keep their names because the frontend reads them.

#### `WardDashboardSummary`
```jsonc
// values illustrative
{
  "ward": Ward,
  "totalComplaints": 12,
  "openComplaints": 12,        // status = OPEN
  "openIncidents": 3,          // OPEN + IN_PROGRESS
  "complaintsLast7d": 0,       // reported_at
  "topCategories": [CategoryCount],  // ≤ 5, this ward
  "topIncidents": [Incident]   // ≤ 5 ACTIVE incidents in this ward, by score
}
```

`category` in `topCategories` is the canonical code; render it through the
`GET /categories` display name, never as-is.

---

## Explorer query conventions

> **Status: adopted 2026-10-13.** Binding on every new or extended
> Explorer endpoint. Not yet implemented by the existing endpoints — see Known
> gaps at the end of this section.

The rules every Explorer-facing endpoint follows — new ones, and existing ones
as they are extended. Written before the endpoints so that they are defined
once; which endpoints are REUSE / EXTEND / NEW is roadmap §35 step 2, and lands
in this section when it is done. Domain definitions behind these rules are in
[data-quality-rules.md](data-quality-rules.md) §3.

### Where it lives

- **Aggregates go on `DashboardController`** (`/dashboard/**`, officer-only),
  extending what is there. No `/ward-stats`, `/ward-analytics`,
  `/ward-overview` family returning overlapping data.
- **Row lists stay on their resource** — `/incidents`, `/complaints`.
- **Explicit response DTOs only.** Never a JPA entity on the wire.
- **Officer-only**, with one exception that already exists: `GET /wards/summary`
  is public and counts-only, and nothing is added to it.

### Shared filter parameters

One meaning per parameter, on every endpoint that accepts it. Repeating a
parameter means OR within it; different parameters AND together.

| Parameter | Meaning | Validation |
|---|---|---|
| `wardId` (repeatable) | `ward_id` — internal id, as on the wire | unknown id → empty result, not 404 (a filter, not a lookup) |
| `category` (repeatable) | canonical code; any alias is canonicalised first; an unresolvable value matches only rows literally carrying it | never 400 |
| `status` (repeatable) | `open` \| `in_progress` \| `resolved` \| `closed` — `resolved` = fixed, `closed` = terminated without a fix | unknown → 400 naming the allowed values |
| `priorityBand` (repeatable) | `low` \| `medium` \| `high` \| `critical`, thresholds server-side | unknown → 400 |
| `from`, `to` | ISO-8601 instants, **filtering `reported_at`**, half-open `[from, to)` | unparseable or `from >= to` → 400 |
| `q` | case-insensitive substring over title/description | trimmed; blank = absent |

- **`from`/`to` always mean report time.** An operator asking "what came in last
  week" means when problems were reported. On an incident the filter applies to
  its members' `reported_at`.
- **No `severity` parameter, ever** — the field does not exist
  ([priority-evaluation.md](priority-evaluation.md) §DECISION).
- **"High priority" is `priorityBand in (high, critical)`**, computed once on
  the server. A client never compares `priorityScore` to a number.

### Lists: pagination, sorting, size

| | |
|---|---|
| Parameters | `limit` (default 50, max 200), `offset` (default 0) |
| Over max | clamped to 200, not 400 — a too-large page is a client bug, not a user error |
| Response | `{ "items": [...], "total": 123, "limit": 50, "offset": 0 }` |
| Sort | `sort=<field>` and `dir=asc\|desc`; each endpoint documents its allowed fields and default; **ties broken by `id`** so pages are stable |
| Aggregates | not paginated, but bounded by construction (≤ 243 wards, ≤ number of categories, fixed top-N) |

Existing `GET /incidents` and `GET /complaints` return bare, unbounded arrays —
see Known gaps. They move to this envelope when the Explorer starts reading
them; the change is breaking, so it ships with the frontend change in one PR.

### Empty and error behaviour

- **No matching data is `200` with an empty list / zero counts**, never `404`.
  The Explorer must be able to tell "widen your filters" from "something
  broke" (roadmap §9).
- `404` only for a path resource that does not exist (`/dashboard/wards/999/summary`).
- Errors use the standard envelope above. A metric that cannot be computed yet
  — resolution time before any incident has been resolved — is `null`, not `0`.

### Time and freshness

- Every window is computed in UTC; responses carry instants with `Z`.
- An aggregate that could be stale says when it was computed. Priority
  freshness is `priorityComputedAt`, already on `Incident`.
- **Resolution data begins 2026-10-06** (V18). A resolution metric for an
  earlier window is `null` with no data, not `0`.
- **Resolution metrics count `resolved` incidents only.** Time to resolve is
  `resolvedAt − min(member reportedAt)`. `closed` incidents were not fixed and
  are never in a resolution figure.

### Known gaps in existing endpoints

Each is latent with today's data and must be fixed before the Explorer reads
through it. Detail in [data-quality-rules.md](data-quality-rules.md) §5.

| Endpoint | Gap |
|---|---|
| ~~`GET /dashboard/summary`, `/dashboard/wards/{id}/summary`~~ | ~~windows counted `created_at`~~ — **fixed 2026-10-13**, now `reported_at` |
| ~~same~~ | ~~`topIncidents` ranked any status~~ — **fixed 2026-10-13**, active only |
| ~~`GET /complaints`~~ | ~~loaded the entire table and filtered in memory~~ — **fixed 2026-10-14** (E4) |
| ~~`GET /incidents`~~ | ~~no pagination; single-valued filters~~ — **fixed 2026-10-13** (E1) |

### Endpoint classification

Roadmap §35 step 2, done 2026-10-13. Every Explorer data requirement in roadmap
§11–16 is mapped to an existing endpoint before anything is built:
**REUSE** as-is, **EXTEND** an existing endpoint, **NEW** only where no
existing endpoint has the same domain responsibility, **DEFER** with a reason.

Who reads what today matters for EXTEND, because some changes are breaking:

| Endpoint | Frontend consumer today |
|---|---|
| `GET /incidents` | `IncidentDashboard` (officer console) — all filters server-side since E1 |
| `GET /incidents/{id}/complaints` | `IncidentDetail` |
| `GET /wards`, `/categories` | console, submit form |
| `GET /wards/summary` | public landing `WardStrip` |
| `GET /dashboard/summary`, `/dashboard/wards/{id}/summary` | **none** |
| `GET /complaints` (officer list) | **none** (`complaintService.list` is unused) |

#### Requirement → endpoint

| Requirement (roadmap §) | Class | Endpoint |
|---|---|---|
| Ward table: complaints, active, high-priority, dominant category, last activity (§11) | **NEW** | N1 `GET /dashboard/wards` |
| Ward map: boundaries (§11) | **NEW** | N4 `GET /wards/boundaries` |
| Ward map: density / priority overlay (§11) | REUSE N1 | choropleth from per-ward counts, not markers (§29) |
| Ward detail: metadata (§12) | REUSE | `GET /wards/{id}` |
| Ward detail: incident summary, priority + status distribution (§12) | **EXTEND** | E6 `GET /dashboard/wards/{wardId}/summary` |
| Ward detail: category distribution (§12) | REUSE N2 | `GET /dashboard/categories?wardId=` — one answer to that question, not two |
| Ward detail: trend (§12) | **NEW** | N3 `GET /dashboard/trends?wardId=` |
| Ward detail: recent incidents (§12) | EXTEND | E1 `GET /incidents?wardId=&sort=lastReportedAt` |
| Category table: volume, active, high-priority, resolution metrics (§13) | **NEW** | N2 `GET /dashboard/categories` |
| Category trend (§13–14) | NEW | N3 `GET /dashboard/trends?groupBy=category` |
| Category → ward distribution (§13–14) | REUSE N1 | `GET /dashboard/wards?category=` |
| Category display names (§14) | REUSE | `GET /categories` |
| Incident list with shared filters (§15) | **EXTEND** | E1 `GET /incidents` |
| First / last reported (§15) | **EXTEND** | E2 `Incident` shape |
| Incident detail + timeline (§16) | REUSE | `GET /incidents/{id}`, `GET /incidents/{id}/complaints` (already ordered by `reported_at`) |
| Complaint drill-down outside an incident | **EXTEND** | E4 `GET /complaints` |
| Dashboard headline figures | **EXTEND** (fix) | E5 `GET /dashboard/summary` |
| "Why are these grouped" — `incident_match_log` (§16) | **DEFER** to Milestone 4 | needs operator language ("40 m away, same problem"), not cosine scores; design with the matching evaluation |
| Last ingestion freshness (§10) | **DEFER** | no feed exists; add when the first one is onboarded |
| Officer-facing ward strip | **not** `GET /wards/summary` | that endpoint is public and counts-only; the Explorer uses N1 |

Net: **4 new endpoints, 5 extended, 0 new controllers** — all implemented by 2026-10-14. N1–N3 sit on
`DashboardController`; N4 on `WardController`, under the already-public
`/wards` prefix.

#### E1 — `GET /incidents` (extend; breaking) — ✅ implemented 2026-10-13

| | |
|---|---|
| Auth | officer |
| Filters | `wardId`, `category`, `status`, `priorityBand` (all repeatable), `from`/`to`, `q` (title + summary). `minPriority` stays accepted for one release, then goes — `priorityBand` replaces it. |
| Date semantics | an incident matches `[from, to)` when **at least one member complaint** was reported in it |
| Sort | `priority` (default: score desc, then `lastReportedAt` desc), `lastReportedAt`, `firstReportedAt`, `complaintCount`, `resolvedAt`; ties by `id` |
| Response | `{ items: Incident[], total, limit, offset }` |
| Max | 200 per page |
| Empty | `200`, `items: []` |
| Breaking | yes — bare array → envelope. Shipped together with `incident.service.ts` + `IncidentDashboard`, which no longer filters in the browser. The console requests `limit=200` and says "N of total" when there are more; past that, the map moves to N1's per-ward aggregate. **Deploy backend and frontend together.** |

#### E2 — `Incident` shape (extend; additive) — ✅ implemented 2026-10-13/14

| Field | Meaning | Status |
|---|---|---|
| `firstReportedAt` | `min(member complaints.reportedAt)` — never `createdAt` | ✅ V21 |
| `lastReportedAt` | `max(member complaints.reportedAt)` | ✅ V21 |
| `owningDepartmentId` | `departmentId` if an officer set one, else the category's default (V19); `null` = triage by hand | ✅ read with the row (`@Formula` on `Incident`) |

Additive, so non-breaking. Stored as `incidents.first_reported_at` /
`last_reported_at` (V21, backfilled) and maintained by
`IncidentAttachmentService.recompute` with `complaintCount`, the way the
centroid already is, rather than aggregated per request.

#### E4 — `GET /complaints` (extend; non-breaking — no consumer) — ✅ implemented 2026-10-14

Officer-only. Filtering is in the query (it used to load the table and
filter in Java). Filters `wardId`, `category`, `status` (repeatable), `from`/`to`
on `reportedAt`, `q` (title + description). Sort `reportedAt` (default desc),
ties by `id`. Envelope, max 200.

#### E5 — `GET /dashboard/summary` (extend; fix) — ✅ implemented 2026-10-14

- ✅ `complaintsLast24h` / `complaintsLast7d` count **`reported_at`**.
- ✅ `topIncidents` ranks **active** incidents only.
- ✅ Adds `highPriorityIncidentCount` (active, `priorityBand in (high, critical)`).

No consumer, so the semantic fix breaks nothing.

#### E6 — `GET /dashboard/wards/{wardId}/summary` (extend) — ✅ implemented 2026-10-14

Same two fixes as E5, plus:

```jsonc
// values illustrative
{
  // …existing fields…
  "highPriorityIncidentCount": 1,
  "lastActivityAt": "2026-09-10T11:02:43Z",        // max(reported_at); null if none
  "priorityDistribution": { "low": 0, "medium": 2, "high": 1, "critical": 0 },  // active incidents
  "statusDistribution": { "open": 3, "in_progress": 0, "resolved": 0, "closed": 0 } // incidents
}
```

Filters `category` (repeatable), `from`/`to`, applied to the four new fields;
the existing fields stay unfiltered for compatibility. 404 for an unknown **or
retired** ward. Bands are assigned with the same `PriorityBand.of` every
`Incident` carries, and every band and status key is always present.

**No `categoryDistribution`** (dropped from the proposal at implementation):
`GET /dashboard/categories?wardId=` already answers "how do this ward's
complaints split by category", with more columns. Two endpoints answering one
question is the drift §6.1 warns about.

#### N1 — `GET /dashboard/wards` (new) — ✅ implemented 2026-10-14

One row per **active** ward. Serves the ward table, the ward map's choropleth
and category→ward distribution.

```jsonc
// 200 — WardExplorerSummary[] (values illustrative)
[{
  "wardId": "491", "wardCode": "1", "wardName": "Kempegowda Ward",
  "complaintCount": 33,
  "activeIncidentCount": 4,
  "highPriorityIncidentCount": 1,
  "dominantCategory": "pothole",     // most complaints; ties → registry sort order; null if none
  "lastActivityAt": "2026-09-10T…Z"  // null if none
}]
```

| | |
|---|---|
| Auth | officer |
| Filters | `category` (repeatable), `from`/`to`. Complaint counts use complaints reported in the window; incident counts use incidents with a member in it. Status and band are not filters here — they are what the columns already split by. |
| Sort | `wardCode` numeric ascending; the client re-sorts (≤ 243 rows) |
| Size | always every active ward — 243 — including zeros, so a map can colour every polygon |
| Empty | wards with no activity return zeros and nulls, not absence |

#### N2 — `GET /dashboard/categories` (new) — ✅ implemented 2026-10-14

One row per category that is active **or** has data.

```jsonc
// 200 — CategoryExplorerSummary[] (values illustrative)
[{
  "category": "pothole",
  "complaintCount": 59,
  "activeIncidentCount": 4,
  "highPriorityIncidentCount": 1,
  "wardCount": 9,                     // wards with ≥1 complaint
  "resolvedIncidentCount": 0,         // RESOLVED only — never CLOSED
  "medianHoursToResolve": null,       // resolvedAt − min(member reportedAt); null when nothing resolved
  "defaultDepartmentId": "1",         // V19; null = triage by hand
  "lastActivityAt": "…Z"
}]
```

Filters `wardId` (repeatable), `from`/`to` (resolution figures: incidents
**resolved** in the window). Ordered by registry `sort_order`. Bounded by the
registry (8 today).

#### N3 — `GET /dashboard/trends` (new) — ✅ implemented 2026-10-14

Complaint counts over time, bucketed by `reported_at`.

| | |
|---|---|
| Auth | officer |
| Params | `from`, `to` (required), `bucket=day\|week` (default `day`), `groupBy=none\|category` (default `none`), plus `wardId`, `category` (repeatable) |
| Response | `{ bucket, series: [{ key: "pothole" \| null, points: [{ start: "…Z", count: 3 }] }] }` — every bucket in range present, zeros included, so a chart never interpolates a gap |
| Max | 400 buckets per series (`day` ≈ 13 months, `week` ≈ 7.5 years); over → 400 naming the limit |
| Weeks | start Monday 00:00 UTC |

`groupBy=ward` is deliberately absent — 243 series is not a chart. A ward's
trend is `?wardId=`.

#### N4 — `GET /wards/boundaries` (new, public) — ✅ implemented 2026-10-14

311 KB for 243 wards (the full-resolution geometry is ~1.4 MB); ~150 ms to
build, then cached by the browser for a day.

```jsonc
// 200, Cache-Control: public, max-age=86400 — GeoJSON
{
  "type": "FeatureCollection",
  "datasetVersion": "2022-delimitation",
  "attribution": "KSRSAC / DataMeet, CC BY-SA 2.5 India",
  "features": [{ "type": "Feature", "geometry": { "type": "MultiPolygon", … },
                 "properties": { "id": "491", "code": "1", "name": "Kempegowda Ward" } }]
}
```

Active wards only. Geometry simplified server-side
(`ST_SimplifyPreserveTopology`, ~10 m tolerance) — display only; **ward
assignment never uses this payload**, it stays `ST_Contains` on the full
geometry. Public because it is open data already published under that licence,
and the attribution must be rendered on the map. Lives under `/wards`, whose
posture is already entirely public.

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

### `POST /internal/ingest/{source}` — bulk external ingestion

The only way externally-sourced complaints enter the system. Shared-secret
header like the rest of `/internal/*`.

```jsonc
// request — an array, max 10,000
[{
  "sourceRecordId": "BBMP-2026-0001",  // required: the feed's own id
  "description": "Large pothole outside the school gate",
  "category": "Road Damage",            // any spelling the registry resolves
  "wardCode": "196",                    // optional: our code, the KGIS code, or the LGD code
  "lat": "12.9250",
  "lng": "77.5938",
  "reportedAt": "2026-09-10T08:30:00Z",
  "status": "REGISTERED",               // the feed's vocabulary; normalised
  "title": null,
  "photoUrl": null
}]

// 200 — ALWAYS 200 when the batch itself was usable
{ "batchId": "12", "source": "bbmp", "received": 7,
  "accepted": 2, "rejected": 4, "unchanged": 1 }
```

**Every field arrives as a string and nothing is bound-validated.** A feed
sending `"lat": "twelve"` must not fail Jackson before the pipeline sees it —
that would give a 400 and no record of which upstream row caused it, and one bad
row would reject a batch of ten thousand good ones.

**200 even when every record was rejected.** The batch was received, examined
and recorded: that is a successful run that found bad data, not a failed
request. A 4xx would tell the caller's scheduler to retry, and retrying a file
full of malformed rows produces the same malformed rows. 400 is reserved for the
request being unusable — empty, over 10,000 records, or a malformed source name.

**`unchanged` is the number to watch.** On a daily re-delivery it should be most
of the batch; that is idempotency working. If it drops to zero the feed has
started re-issuing identifiers and every run is about to create duplicates.

Rejected records keep their raw payload and machine-readable reasons. The
report:

```sql
SELECT field, code, occurrences, example_payload FROM ingestion_rejections
WHERE source = 'bbmp' ORDER BY occurrences DESC;
```

Full behaviour — validation rules, normalisation, idempotency — in
`docs/service-boundaries.md` and the `IngestionValidator` javadoc.

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
  "photoUrl": null,           // read-only; never accepted on create — see below
  "reportedAt": "2026-08-29T10:15:00Z",  // when it was REPORTED; see note below
  "createdAt": "2026-08-29T10:15:00Z",   // when the row was created here
  "updatedAt": "2026-08-29T10:15:00Z"
}
```

### `Incident`
```jsonc
{
  "id": "45",
  "wardId": "3",
  "departmentId": "2",
  "owningDepartmentId": "2",  // departmentId, else the category's default (V19); null = triage by hand
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
  "priorityComputedAt": "…",  // when the score was last DERIVED; null if never
  "resolvedAt": null,         // set only while status is `resolved`; see below
  "firstReportedAt": "…",     // min(member reportedAt) — "age" is measured from this
  "lastReportedAt": "…",      // max(member reportedAt)
  "createdAt": "…",
  "updatedAt": "…"
}
```

**`resolvedAt` is maintained with `status`, not alongside it.** It is stamped
when an incident enters `resolved` and **cleared when it leaves** — a reopened
incident has no resolution time, because a stamp left behind would be counted by
every "resolved in this period" query while the problem is demonstrably still
open. Re-sending `resolved` on an already-resolved incident does not move it, so
editing a closed incident's summary cannot rewrite when it was closed.

Do not compute resolution time as `updatedAt − createdAt`. `PriorityRefreshJob`
touches every open incident every few minutes, so that subtraction measures the
refresh sweep. `resolvedAt` is the only field that answers the question.

Complaints have no equivalent field, deliberately: the incident is the entity
that gets assigned and closed, and two resolution timestamps would disagree
whenever a member complaint is resolved inside a still-open incident.

**`priorityComputedAt` is not `updatedAt`.** The priority formula has a
time-dependent term (age, saturating at 14 days), so a stored score drifts with
no write at all. `updatedAt` moves on any write — a status change, a department
assignment — and so cannot say whether the *score* is current. This can, and a
ranked queue is entitled to say how old its ranking is. `PriorityRefreshJob`
sweeps open incidents on it (`app.priority.refresh.*`); resolved and closed
incidents are left alone, because their score records how urgent the problem was
while it was live.

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

**`reportedAt` vs `createdAt` on `Complaint`.** They answer different
questions and are equal only for a complaint filed through this site:

| Field | Means |
|---|---|
| `reportedAt` | when the problem was reported — the upstream timestamp for an imported complaint, which can be months older than the row |
| `createdAt` | when CivicPulse created the row. Never rewritten, so it is arrival order |

Render "reported N days ago" from `reportedAt`. Anything reasoning about the
order things reached us wants `createdAt`. The priority formula's
time-dependent terms read `reportedAt`; the reconcile queue orders by `id`.

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

### `Category`
```jsonc
{
  "code": "pothole",                       // stable identifier — the value clients submit
  "name": "Pothole / road damage",         // officer-facing canonical name
  "displayName": "Pothole or damaged road" // resident-facing label — the only one to render
}
```

`code` never changes once seeded: it is the literal value stored in
`complaints.category` and `incidents.category`, and therefore what every
exact-equality filter compares. `name` and `displayName` are labels and may be
reworded freely.

The raw string a caller sent is preserved server-side in
`complaints.source_category` for audit, and is not exposed on the wire.

### `Ward`
```jsonc
{
  "id": "538",
  "code": "48",               // BBMP ward number (1–243), used in reference numbers
  "name": "Jnana Bharathi",
  "zone": null,               // nullable — null for every imported ward (no zone in the source)
  "lat": 12.9478675,          // ST_PointOnSurface, guaranteed inside the ward; nullable
  "long": 77.5057219
}
```

Note `id` and `code` differ: ward 48 has `id: "538"`. Reference numbers use
`code`, so a resident receipt reads `W48` and matches what the UI shows them.
`name` is a mutable display label and must never feed a printed identifier.

`lat`/`long` are a label point, not a geometric centroid: a true centroid of a
concave ward can fall in its neighbour. Boundaries are not on the wire.

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
- [x] **Refresh-token revocation.** Resolved by V9: refresh tokens are
      server-side sessions in `refresh_tokens` (`revoked_at`,
      `revoked_reason`). `POST /auth/logout` revokes the current session and
      `POST /auth/logout-all` revokes every session for the caller.
- [x] **Photo upload.** **Decided: direct-to-storage with a SERVER-ISSUED
      upload URL** (intent -> direct PUT -> confirm). Neither original option
      was right: multipart holds a request thread for the length of every
      upload, and a caller-supplied `photoUrl` lets anyone put arbitrary content
      in front of an officer — that field has been removed from
      `POST /complaints`. Not implemented; see `photo-upload-contract.md`.

_Living document — update it in the same PR as any cross-service change._
