# Service Boundary Decisions

Resolve all three of these **before Phase 3**. Without this written down,
Spring/Node ownership tends to blur under deadline pressure (see Risk
Watchlist #4 in the roadmap).

## 1. Who owns priority calculation?

- **Tension:** it's deterministic logic (fits Spring Boot's style) but
  depends on incident data that the Node service computes (fits Node).
- **Decision:** _TBD — pick one owner; the other consumes it via API._
- **Owner:**
- **Consumed by:**

## 2. Who writes to the `Incident` table, and how does `incident_id` get
   written back to `Complaint`?

- **Options:** synchronous call vs. a queue/event.
- **Decision:** _TBD_

## 3. Who owns migrations for shared tables?

- **Goal:** avoid both services freely modifying the same tables.
- **Decision:** _TBD_

---

_Update this file as decisions are made in Phase 0. Link the PR/commit that
implements each decision once resolved._
