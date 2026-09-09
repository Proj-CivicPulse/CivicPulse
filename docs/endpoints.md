# API Endpoints — Tracking

Checklist of every planned endpoint across all three services. Check an item
off once it's implemented **and** documented in
[`api-contract.md`](api-contract.md) (for anything crossing the Spring↔Node
boundary).

## Decisions

- [x] **Citizen auth requirement** — **submission is open/anonymous;
      tracking requires an account.** `POST /complaints` is public;
      `GET /complaints/mine` requires the `citizen` role. Already reflected
      in `SecurityConfig`, in the nullable `complaints.user_id` column, and
      in the frontend's public `/complaints/new` route.
- [x] **Priority calculation ownership** — **backend-spring**, recomputed
      inline whenever an incident's membership changes. See
      `service-boundaries.md` decision 1.
- [x] **Incident writes / `incident_id` write-back** — **backend-spring**, in
      one transaction via `POST /internal/incidents/attach`. The trigger to Node
      is **async** with a circuit breaker + naive fallback + reconcile sweep.
      See decision 2.
- [x] **Shared-table migrations** — **backend-spring**, via Flyway.
      Node never runs DDL. See decision 3.

### ⚠️ Still open

- [ ] **Photo upload mechanism** — direct-to-storage upload (client sends a
      `photoUrl` string) vs. a multipart upload endpoint on Spring. Current
      frontend scaffold (`complaint.service.ts`) assumes the former.
- [x] **Auth between services** — resolved: `/internal/*` requires the
      `X-Internal-Token` header, compared in constant time, failing closed when
      `INTERNAL_TOKEN` is blank. Rotation policy is still open.

---

## Spring Boot (`backend-spring`)

### Health
- [x] `GET /health` — real DB check (`SELECT 1`), `{status:'ok'|'error'}`, 200/503

### Auth — Phase 0
- [x] `POST /auth/register` *(not originally listed; always creates a citizen)*
- [x] `POST /auth/login` — sets httpOnly cookies, returns `{user}`.
      Rate limited: 5 failures per email and 50 per IP in 15 min, then a
      15-minute lockout answered as `429 RATE_LIMITED` with `Retry-After`
- [x] `POST /auth/logout` — revokes this session, clears cookies, 204
- [x] `POST /auth/logout-all` — revokes every session for the user, 204.
      **Requires authentication**, unlike the other four
- [x] `GET /auth/me` — session restore, 401 when unauthenticated
- [x] `POST /auth/refresh` — rotates the token pair. The presented refresh
      token is single-use; replaying one after rotation is treated as theft
      and revokes the whole session (401)
- [x] `POST /auth/verify-email` — `{token}` from the verification email, 204.
      Public: the holder is usually signed out and the token is the credential
- [x] `POST /auth/resend-verification` — 204. **Requires authentication**; the
      address comes from the session, so it cannot be aimed at another inbox
- [x] `POST /auth/forgot-password` — `{email}`, **always 204** whether or not
      the address has an account (no enumeration). Rate limited
- [x] `POST /auth/reset-password` — `{token, password}`, 204. Revokes every
      session for the user and clears cookies

Verification is **recorded, not enforced**: `user.emailVerified` is exposed on
the user but nothing server-side gates on it. Filing a complaint is already
open and anonymous, so a hard gate would push people to the anonymous path and
turn any mail failure into a locked account.

**There is no OTP.** Both email flows are single-use *links*, never codes typed
into a form, and registration completes in one step with no interstitial
screen. Login checks email and password hash only. Email delivery is stubbed by
default (`EMAIL_PROVIDER=log`), so auth has no runtime email dependency.

### Officers — Phase 0/1
- [ ] `GET /officers`
- [ ] `GET /officers/{id}`
- [ ] `POST /officers`
- [ ] `PUT /officers/{id}`
- [ ] `DELETE /officers/{id}`

### Wards & Departments — Phase 0 (reference data)
- [x] `GET /wards` — public
- [x] `GET /wards/{id}` — public
- [x] `GET /wards/resolve?lat=&long=` — public; derives the ward from a coordinate pair, 404 beyond 25 km
- [x] `GET /wards/summary` — **public**; landing-page ward strip. Counts, never rows
- [x] `GET /departments`
- [x] `GET /departments/{id}`

### Complaints — Phase 1, P0
- [x] `POST /complaints` — public; `wardId` optional, derived from coordinates when absent
- [x] `GET /complaints` — **officer only**; this is every resident complaint text
- [x] `GET /complaints/{id}` — citizen reads only their own, 404 (not 403) otherwise
- [x] `GET /complaints/mine` — citizen
- [x] `PATCH /complaints/{id}` — **officer only**; previously fell through to `anyRequest().authenticated()`
- [ ] `POST /complaints/{id}/photo` *(only if multipart upload is chosen — see open decisions)*

### Incidents — Phase 2/3
- [x] `GET /incidents` — `?wardId&category&status&minPriority`, priority desc
- [x] `GET /incidents/{id}`
- [x] `GET /incidents/{id}/complaints`
- [x] `PATCH /incidents/{id}` — does not recompute priority; status is not a model input
- [ ] `POST /incidents/{id}/merge` *(manual merge override)*
- [ ] `POST /incidents/{incidentId}/complaints/{complaintId}/unlink` *(manual false-merge override)*

> Still deferred. Semantic matching has landed, but the officer-facing override
> is separate work. The transactional *reattach* it needs already exists
> internally — `IncidentAttachmentService` moves a complaint between incidents
> and deletes an emptied one — driven today only by `MatchReconciliationJob`.
> Client stubs for them were removed — a service method calling an endpoint that
> returns 404 is worse than no method, because it type-checks. Build the server
> side and the client together when the need is real.

### Dashboard — Phase 4
- [x] `GET /dashboard/summary`
- [x] `GET /dashboard/wards/{wardId}/summary`

### Analytics — Phase 5
- [ ] `GET /analytics/trends`
- [ ] `GET /analytics/stale-incidents`
- [ ] `GET /analytics/rising-categories`

### Internal — called by Node (see `api-contract.md`)

All require the `X-Internal-Token` header, and **fail closed** when
`INTERNAL_TOKEN` is blank.

- [x] `GET /internal/complaints/{id}`
- [x] `POST /internal/incidents/attach` — renamed from `/internal/incidents/{id}/complaints`: `incidentId` may be null ("start a new incident") and a null cannot occupy a path segment
- [x] `POST /internal/incidents/{id}/recompute` — re-derive count/centroid/priority only. Separate from `attach` on purpose: attach records a *decision* (stamps `matching_status`, appends an `incident_match_log` row), and reusing it as a recompute hook would fabricate matching events that never happened
- [x] `GET /internal/wards/{id}`

---

## Node.js (`backend-node`)

### Health
- [x] `GET /health` — real DB check (`SELECT 1`), `{status:'ok'|'error'}`, 200/503

### Complaint processing — Phase 2 (core bottleneck)
- [x] `POST /complaints/{id}/process` — CAS claim → embed (Gemini) → candidate
      pre-filter → single-linkage similarity → `POST /internal/incidents/attach`.
      `?force` / `?reembed`. Requires `X-Internal-Token`. Driven by Spring's
      async trigger and its `MatchReconciliationJob`.

### Copilot — Phase 6
- [ ] `POST /copilot/query` — *route exists, body validated, returns 501*

### Hotspots — Phase 7, conditional
- [ ] `GET /hotspots/predict` *(proxies to `ml-hotspots`)* — *route exists, returns 501*

---

## Python/FastAPI (`ml-hotspots`) — internal only, Phase 7 conditional

- [ ] `GET /health`
- [ ] `POST /predict`

---

_Update this file in the same PR that implements an endpoint. Cross-service
endpoints also need `api-contract.md` updated with the actual request/response
shape once it's real._