# API Contract — Spring Boot ⇄ Node.js

This document is the single source of truth for how `backend-spring` and
`backend-node` talk to each other. Update this **before** changing any
endpoint that the other service depends on — treat it as a contract, not
documentation-after-the-fact.

Resolve and fill this in as part of **Phase 0**, alongside
[`service-boundaries.md`](service-boundaries.md).

---

## Conventions

- Base URL (Spring): `TODO: e.g. http://localhost:8080/api`
- Base URL (Node): `TODO: e.g. http://localhost:3001/api`
- Auth between services: `TODO — shared internal secret? service JWT? open on localhost for MVP?`
- Content type: `application/json` unless noted
- Error format: `TODO — agree on a standard error shape, e.g.`
  ```json
  { "error": { "code": "STRING", "message": "human readable" } }
  ```

---

## Spring Boot → Node.js

Endpoints Spring calls on Node.

### `POST /embeddings/generate`
- **Purpose:** TODO — e.g. triggered on complaint creation to generate + store embedding
- **Request body:**
  ```json
  { "complaint_id": "TODO", "text": "TODO" }
  ```
- **Response:**
  ```json
  { "TODO": "TODO" }
  ```
- **Sync or async?** TODO — direct call vs. queue/event
- **Called from:** TODO (e.g. `ComplaintService.createComplaint`)

### `POST /copilot/query`
- **Purpose:** officer asks a question, Node retrieves data + calls LLM
- **Request body:**
  ```json
  { "officer_id": "TODO", "query": "TODO", "ward_id": "TODO (optional)" }
  ```
- **Response:**
  ```json
  { "answer": "TODO", "sources": ["TODO"] }
  ```

---

## Node.js → Spring Boot

Endpoints Node calls on Spring.

### `GET /internal/complaints/{id}`
- **Purpose:** Node fetches full complaint context when generating embeddings / grounding Copilot
- **Response:** TODO — match the `Complaint` entity shape

### `POST /internal/incidents/{id}/complaints`
- **Purpose:** attach a matched complaint to an (existing or new) incident
- **Request body:**
  ```json
  { "complaint_id": "TODO", "incident_id": "TODO" }
  ```
- **Notes:** depends on the Phase 0 decision in `service-boundaries.md` re:
  who writes to the `Incident` table and how `incident_id` gets written back
  to `Complaint`.

### `GET /internal/wards/{id}`
- **Purpose:** ward metadata for candidate filtering before similarity comparison
- **Response:** TODO

---

## Shared data shapes

Keep these in sync with the Postgres schema (`docs/roadmap.md`,
Phase 0 deliverable). Update whenever the schema changes.

### `Complaint`
```json
{
  "id": "TODO",
  "category": "TODO",
  "ward_id": "TODO",
  "lat": "TODO",
  "long": "TODO",
  "description": "TODO",
  "photo_url": "TODO",
  "status": "TODO",
  "incident_id": "TODO (nullable until matched)",
  "created_at": "TODO"
}
```

### `Incident`
```json
{
  "id": "TODO",
  "ward_id": "TODO",
  "category": "TODO",
  "priority_score": "TODO",
  "priority_reasons": ["TODO"],
  "status": "TODO",
  "created_at": "TODO",
  "updated_at": "TODO"
}
```

---

## Open questions (blockers before Phase 3)

- [ ] Auth mechanism between the two services
- [ ] Sync vs. async for embedding generation and incident-matching calls
- [ ] Standard error response shape
- [ ] Who owns priority calculation (see `service-boundaries.md`)
- [ ] Retry/timeout behavior if Node is down when Spring calls it (or vice versa)

---

_This is a living document — update it in the same PR as any change to a
cross-service endpoint._
