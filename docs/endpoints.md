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
      in the frontend's public `/submit-complaint` route.
- [x] **Priority calculation ownership** — **backend-spring**, recomputed
      inline whenever an incident's membership changes. See
      `service-boundaries.md` decision 1.
- [x] **Incident writes / `incident_id` write-back** — **backend-spring**,
      synchronously, in one transaction via
      `POST /internal/incidents/attach`. See decision 2.
- [x] **Shared-table migrations** — **backend-spring**, via Flyway.
      Node never runs DDL. See decision 3.

### ⚠️ Still open

- [ ] **Photo upload mechanism** — direct-to-storage upload (client sends a
      `photoUrl` string) vs. a multipart upload endpoint on Spring. Current
      frontend scaffold (`complaint.service.ts`) assumes the former.
- [ ] **Auth between services** — `/internal/*` is unauthenticated and
      localhost-only today. See `api-contract.md` open questions.

---

## Spring Boot (`backend-spring`)

### Health
- [x] `GET /health` — real DB check (`SELECT 1`), `{status:'ok'|'error'}`, 200/503

### Auth — Phase 0
- [x] `POST /auth/register` *(not originally listed; always creates a citizen)*
- [x] `POST /auth/login` — sets httpOnly cookies, returns `{user}`
- [x] `POST /auth/logout` — clears cookies, 204
- [x] `GET /auth/me` — session restore, 401 when unauthenticated
- [x] `POST /auth/refresh` — rotates the token pair

### Officers — Phase 0/1
- [ ] `GET /officers`
- [ ] `GET /officers/{id}`
- [ ] `POST /officers`
- [ ] `PUT /officers/{id}`
- [ ] `DELETE /officers/{id}`

### Wards & Departments — Phase 0 (reference data)
- [ ] `GET /wards`
- [ ] `GET /wards/{id}`
- [ ] `GET /departments`
- [ ] `GET /departments/{id}`

### Complaints — Phase 1, P0
- [ ] `POST /complaints`
- [ ] `GET /complaints`
- [ ] `GET /complaints/{id}`
- [ ] `GET /complaints/mine`
- [ ] `PATCH /complaints/{id}`
- [ ] `POST /complaints/{id}/photo` *(only if multipart upload is chosen — see open decisions)*

### Incidents — Phase 2/3
- [ ] `GET /incidents`
- [ ] `GET /incidents/{id}`
- [ ] `GET /incidents/{id}/complaints`
- [ ] `PATCH /incidents/{id}`
- [ ] `POST /incidents/{id}/merge` *(manual merge override)*
- [ ] `POST /incidents/{incidentId}/complaints/{complaintId}/unlink` *(manual false-merge override)*

### Dashboard — Phase 4
- [ ] `GET /dashboard/summary`
- [ ] `GET /dashboard/wards/{wardId}/summary`

### Analytics — Phase 5
- [ ] `GET /analytics/trends`
- [ ] `GET /analytics/stale-incidents`
- [ ] `GET /analytics/rising-categories`

### Internal — called by Node (see `api-contract.md`)
- [ ] `GET /internal/complaints/{id}`
- [ ] `POST /internal/incidents/{id}/complaints`
- [ ] `GET /internal/wards/{id}`

---

## Node.js (`backend-node`)

### Health
- [x] `GET /health` — real DB check (`SELECT 1`), `{status:'ok'|'error'}`, 200/503

### Complaint processing — Phase 2 (core bottleneck)
- [ ] `POST /complaints/{id}/process` — *route exists, returns 501 `NOT_IMPLEMENTED`*

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