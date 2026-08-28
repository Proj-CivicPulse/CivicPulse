# API Endpoints — Tracking

Checklist of every planned endpoint across all three services. Check an item
off once it's implemented **and** documented in
[`api-contract.md`](api-contract.md) (for anything crossing the Spring↔Node
boundary).

## ⚠️ Open decisions — blocking some endpoints below

- [ ] **Citizen auth requirement** — does submitting/tracking a complaint
      require an account, or is submission open/anonymous? Affects
      `POST /complaints` and `GET /complaints/mine`.
- [ ] **Priority calculation ownership** — Spring or Node? See
      `service-boundaries.md`. Affects whether priority is computed inline
      in Spring or via a dedicated Node endpoint.
- [ ] **Photo upload mechanism** — direct-to-storage upload (client sends a
      `photoUrl` string) vs. a multipart upload endpoint on Spring. Current
      frontend scaffold (`complaint.service.ts`) assumes the former.

---

## Spring Boot (`backend-spring`)

### Health
- [ ] `GET /health`

### Auth — Phase 0
- [ ] `POST /auth/login`
- [ ] `POST /auth/logout`
- [ ] `GET /auth/me`
- [ ] `POST /auth/refresh`

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
- [ ] `GET /health`

### Complaint processing — Phase 2 (core bottleneck)
- [ ] `POST /complaints/{id}/process`

### Copilot — Phase 6
- [ ] `POST /copilot/query`

### Hotspots — Phase 7, conditional
- [ ] `GET /hotspots/predict` *(proxies to `ml-hotspots`)*

---

## Python/FastAPI (`ml-hotspots`) — internal only, Phase 7 conditional

- [ ] `GET /health`
- [ ] `POST /predict`

---

_Update this file in the same PR that implements an endpoint. Cross-service
endpoints also need `api-contract.md` updated with the actual request/response
shape once it's real._