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
| Embeddings | Google Gemini `gemini-embedding-001`, 1536-dim | Required to convert complaint text into comparable vectors. Provider was left open by the project scope; **settled in Phase 2** on multilingual grounds — complaint text here mixes English, Hindi and Kannada |
| LLM | External LLM API | Needed only for summaries and Copilot — not for structured complaint processing itself |
| ML (predictive hotspots) | Python + scikit-learn/pandas | Only if Phase 7 is triggered by sufficient historical data — never assumed upfront |
| Real-time updates (Socket.IO/WebSockets) | Not included by default | Not a stated requirement. Periodic refresh/polling is sufficient for an MVP officer dashboard; add only if the team decides staleness is a real problem |

---

## Backend Service Boundary — settled

- **Spring Boot owns:** auth, users, complaint CRUD, officer CRUD, dashboard APIs, general app backend
- **Node.js owns:** embedding generation, incident matching, Copilot/LLM orchestration
- **All three open questions are resolved** — full reasoning in
  [`service-boundaries.md`](service-boundaries.md):
  - **Priority calculation → Spring Boot.** Every input is already a SQL query over tables Spring owns; nothing about it needs an embedding.
  - **`Incident` writes → Spring Boot, always.** Node decides the match and asks Spring to write it, in one transaction. The *trigger* to Node is **asynchronous** (Phase 2), so a slow embedding call never delays a citizen's submission — with a circuit breaker, a naive fallback, and a reconciliation sweep behind it. No queue was added.
  - **Migrations → Spring Boot, via Flyway.** Node never runs DDL; a column it needs (like `complaints.embedding`) is added there.

That agreement is what Risk Watchlist #4 exists to protect — re-read it before
changing anything that crosses the boundary.

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

## Phase 2 — Embeddings + Incident Matching (Weeks 3–4) — P0, core bottleneck ✅
- On complaint creation, Node service calls embedding API, stores vector via pgvector
- Candidate filtering first (ward + category + active status) before running similarity comparison — avoids comparing against every incident
- Similarity threshold determined empirically; log scores for later evaluation
- Merge into existing incident or create new one
- **Deliverable:** working matching pipeline — this is the component your paper will evaluate on precision/recall/F1

**Delivered.** As built, with the decisions the plan left open:

| | |
|---|---|
| Provider | Gemini `gemini-embedding-001`, **1536-dim**, `SEMANTIC_SIMILARITY`, L2-normalised in Node |
| Scoring | **Single-linkage** — an incident scores as the max cosine similarity to any one member complaint. Named honestly in the methodology: it is incremental single-linkage clustering, chaining risk and all |
| Threshold | `MATCH_SIMILARITY_THRESHOLD`, ships at `0.75`. A starting point, not a finding |
| Trigger | Async, `AFTER_COMMIT`. Circuit breaker → naive fallback (`degraded`) → reconciliation sweep. A citizen never waits on an embedding call |
| Score logging | `incident_match_log` — every decision with `top_similarity`, `candidate_count`, `threshold`, the sibling complaint that won, and a `created` flag separating a join from a split. Sub-threshold scores are recorded too, so the threshold can be re-chosen from data rather than re-run |

Two things this hands Phase 8 for free: every naive fallback is a labelled
baseline decision, and every reconcile move is a labelled *disagreement*
between that baseline and the real pipeline.

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

### What Phase 2 already handed you

`incident_match_log` is the dataset. Every row is one decision with its
`top_similarity`, the sibling complaint it scored against, `candidate_count`,
the `threshold` in force, and a `created` flag separating a join from a split.
Sub-threshold scores are recorded too, so a different threshold can be evaluated
offline without re-running the pipeline or paying for re-embedding.

Two labelled comparisons come for free: every `matcher = NAIVE` row is a
baseline decision, and every `decision = RECONCILED` row is a case where the
semantic matcher *disagreed* with that baseline and moved the complaint.

**First measurements** (148 seeded complaints, `gemini-embedding-001` @ 1536,
threshold 0.75):

| band | decisions | created a new incident |
|---|---|---|
| no candidate | 36 | 36 |
| 0.75–0.80 | 11 | 0 |
| 0.80–0.90 | 39 | 0 |
| 0.90+ | 64 | 0 |

Nothing scored between 0.70 and 0.75, and **no scored decision was rejected** —
the threshold turned nothing away on this corpus. A separate adversarial probe
(construction noise vs. barking dogs, same ward and category, ~10 m apart, which
the naive grouper *would* have merged) scored 0.7499 and was correctly split.

**Two traps before you re-tune it.** First, fragmentation is not evidence about
the threshold: 10 of 24 seed hotspots split, all with `candidate_count = 0` —
the concurrency race, not similarity. Filter on `candidate_count > 0` before
drawing any conclusion. Second, the seed corpus cannot produce a false merge at
all, because one ward+category is one problem by construction; it will always
flatter a low threshold. Hand-label same-ward/same-category/**different-problem**
pairs before changing the number.

---

## Risk Watchlist
1. **Similarity threshold tuning** — budget real time, it won't be a one-shot config
2. **False merges** — two distinct nearby issues can read as semantically similar; give officers a manual override. Single-linkage scoring (max similarity to any member) adds a chaining risk on top — mitigated by a conservative threshold, the logged scores, and that override
3. **LLM cost/latency creep** — cache Copilot responses where reasonable, don't call the LLM on every dashboard load
4. **Service boundary drift** — without the Phase 0 agreement holding, Spring/Node ownership tends to blur under deadline pressure
5. **Scope creep into P2** — don't start Phase 7 until Phases 0–6 are demo-solid
6. **`degraded` backlog during a Node outage** — a prolonged backend-node outage leaves a growing pile of `degraded`/`pending` complaints, semantically unmatched. `MatchReconciliationJob` logs the backlog count every run it is non-zero so it can't creep up unseen; watch that line during the demo
7. **Duplicate incidents from simultaneous reports** — complaints filed seconds apart are matched concurrently and can each create an incident, because neither sees the other's embedding yet. Self-corrects on the next reconcile sweep (which runs serially), so it is transient — but it is worth knowing before a live demo, and it means incident counts taken immediately after a burst of submissions read high
