# CivicPulse — Implementation Roadmap
### Requirement-driven, based on what the project actually needs

---

## Tech Stack — What's required and why

| Layer | Choice | Why it's actually needed |
|---|---|---|
| Frontend | React | Specified in scope; officer dashboard and citizen portal both benefit from shared component patterns |
| Backend | Spring Boot (core APIs) + Node.js (AI layer) | Splits stable, transactional business logic from the fast-iterating AI/matching layer — these two genuinely evolve at different speeds during the research phase |
| Database | PostgreSQL | System of record; the Complaint/Incident/Ward/Department relationships are inherently relational |
| Vector search | pgvector | Core requirement, not optional — semantic incident matching has no other way to compare complaint embeddings |
| Maps | Leaflet + OpenStreetMap | Free, lightweight, sufficient for markers and basic hotspot display; no licensing overhead |
| Geospatial | PostGIS | **Deferred.** Only add if ward-boundary lookups become a real blocker — lat/long + ward_id covers P0 needs |
| Auth | JWT | Needed for citizen vs. officer role-based access control |
| Embeddings | External embedding API | Required to convert complaint text into comparable vectors; provider not fixed by the project scope |
| LLM | External LLM API | Needed only for summaries and Copilot — not for structured complaint processing itself |
| ML (predictive hotspots) | Python + scikit-learn/pandas | Only if Phase 7 is triggered by sufficient historical data — never assumed upfront |
| Real-time updates (Socket.IO/WebSockets) | Not included by default | Not a stated requirement. Periodic refresh/polling is sufficient for an MVP officer dashboard; add only if the team decides staleness is a real problem |

---

## Backend Service Boundary — needs an explicit team decision

- **Spring Boot owns:** auth, users, complaint CRUD, officer CRUD, dashboard APIs, general app backend
- **Node.js owns:** embedding generation, incident matching, Copilot/LLM orchestration
- **Still open — resolve before Phase 3:**
  - Who owns priority calculation? It's deterministic logic (fits Spring Boot) but depends on incident data the Node service computes (fits Node). Pick one owner and have the other consume it via API.
  - Which service writes to the `Incident` table, and how does the resulting `incident_id` get written back to `Complaint` records — synchronous call or a queue?
  - Who owns migrations for shared tables? Avoid both services freely modifying the same tables (doc's own caution).

Settle this in Phase 0 — it's the single biggest source of avoidable friction later.

---

## Phase 0 — Foundations (Week 1)
- Repo structure: Spring Boot service, Node.js AI service, React frontend
- Postgres schema: `User`, `Ward`, `Department`, `Complaint`, `Incident`
- Resolve the open service-boundary questions above, in writing
- JWT auth (citizen + officer roles) in Spring Boot
- **Deliverable:** running skeleton, both services talking to Postgres, agreed API contract between them

## Phase 1 — Complaint Ingestion (Weeks 2–3) — P0
- Complaint submission API (category, ward, lat/long, description, photo) in Spring Boot
- Structured storage in Postgres
- **Deliverable:** citizens can submit complaints; stored and queryable

## Phase 2 — Embeddings + Incident Matching (Weeks 3–4) — P0, core bottleneck
- On complaint creation, Node service calls embedding API, stores vector via pgvector
- Candidate filtering first (ward + category + active status) before running similarity comparison — avoids comparing against every incident
- Similarity threshold determined empirically; log scores for later evaluation
- Merge into existing incident or create new one
- **Deliverable:** working matching pipeline — this is the component your paper will evaluate on precision/recall/F1

## Phase 3 — Explainable Priority Engine (Week 5) — P0
- Rule-based formula: complaint volume, growth rate, incident age, geographic spread
- Store the *reasons*, not just a score — needed for both the officer UI and the paper's explainability claims
- **Deliverable:** every incident carries a priority and a human-readable justification

## Phase 4 — Officer Dashboard + Map (Weeks 5–6) — P0
- React dashboard: City → Ward → Incident → Complaints drill-down
- Leaflet map with incident markers, colored by priority
- Standard REST fetch/refresh — no real-time infrastructure needed for MVP
- **Deliverable:** demo-able command-center UI

## Phase 5 — Trends & Analytics (Week 7) — P0/P1
- Standard SQL aggregation queries — no AI required here
- Category/ward trend charts (any React charting library)
- **Deliverable:** "what's increasing," "what's stale" views

## Phase 6 — Officer Copilot (Weeks 8–9) — P1
- Grounded, not generic: intent → query CivicPulse's own data → LLM generates answer from retrieved context only
- Node service orchestrates the LLM call with retrieved data injected
- **Deliverable:** officer can ask "what's urgent in Ward 17" and get a grounded, data-backed answer

## Phase 7 — Predictive Hotspots (Week 9, conditional) — P2
- **Gate on data first:** check volume, timestamp cleanliness, coordinate reliability before committing any time here
- If viable: isolated Python service (scikit-learn/pandas), called from Node — doesn't touch the core stack
- **Deliverable:** only build if the data genuinely supports it; otherwise this time goes to Phase 8

## Phase 8 — Evaluation + Paper (Weeks 10–12)
- Incident matching: precision/recall/F1 against manually labeled merges
- Priority engine: comparison against a naive baseline (e.g., complaint-count-only), human/expert agreement check
- Write up methodology and results in IEEE format
- Polish demo, record a backup video in case of live-demo failure
- **Deliverable:** paper draft + rehearsed demo

---

## Risk Watchlist
1. **Similarity threshold tuning** — budget real time, it won't be a one-shot config
2. **False merges** — two distinct nearby issues can read as semantically similar; give officers a manual override
3. **LLM cost/latency creep** — cache Copilot responses where reasonable, don't call the LLM on every dashboard load
4. **Service boundary drift** — without the Phase 0 agreement holding, Spring/Node ownership tends to blur under deadline pressure
5. **Scope creep into P2** — don't start Phase 7 until Phases 0–6 are demo-solid
