# CivicPulse — Roadmap and Status

**The single source for what is done and what is left.** Updated 2026-10-21.
It replaces the earlier phase documents (`roadmap.md` v1, `post-midsem-roadmap.md`,
`next-phases-plan.md`, `release-readiness.md`, `audit-register.md`), which are
in git history if anyone needs them.

Reference docs, which describe how things work rather than when, stay as they
are: [api-contract.md](api-contract.md), [endpoints.md](endpoints.md),
[service-boundaries.md](service-boundaries.md), [matching-contract.md](matching-contract.md),
[matching-evaluation.md](matching-evaluation.md), [priority-evaluation.md](priority-evaluation.md),
[data-quality-rules.md](data-quality-rules.md), [ward-data.md](ward-data.md),
[category-dataset-audit.md](category-dataset-audit.md),
[photo-upload-contract.md](photo-upload-contract.md), [railway-database.md](railway-database.md).

---

## At a glance

| # | Phase | Status |
|---|---|---|
| 0 | Foundations | ✅ Done |
| 1 | Complaint ingestion | ✅ Done |
| 2 | Embeddings + incident matching | ✅ Done |
| 3 | Explainable priority engine | ✅ Done (calibration is Phase 8) |
| 4 | Officer dashboard + map | ✅ Done |
| 5 | Trends + analytics | ✅ Done (delivered as the Explorer, Phase 6) |
| S | Stabilisation + data foundation | ✅ Done |
| 6 | Explorer (wards, categories, incidents) | ✅ Done |
| 7 | Matching evaluation | ✅ Done — threshold set to 0.84 |
| 8 | Priority calibration | ⬜ Not started — needs an officer |
| 9 | Hotspots | ✅ Done (2026-10-21) |
| 10 | Operational intelligence ("needs attention") | ⬜ Not started — after 9 |
| 11 | Decision support | ⬜ Not started — after 8–10 and 12 |
| 12 | Officer Copilot | 🟡 Backend live; panel + eval left (Manya) |
| 13 | Paper + demo | 🟡 Matching and system sections ready |

**Build order from here:** 12 (Manya) runs in parallel with 8's tooling and 9.
Then 10 → 11, and 13 throughout.

---

## Done

### 0 — Foundations ✅
Spring Boot core API, Node AI service, React frontend. Flyway owns the schema.
JWT auth in httpOnly cookies with citizen and officer roles. Service boundary
decided in writing ([service-boundaries.md](service-boundaries.md)).

### 1 — Complaint ingestion ✅
Public anonymous submission. Reference numbers (`CP-2026-W48-00001`). Reverse
geocoding with a cache. Ward from coordinates by PostGIS point-in-polygon over
243 real BBMP wards; out-of-area coordinates are refused. Citizen "my
complaints". Photo upload is **deferred**, with its contract approved
([photo-upload-contract.md](photo-upload-contract.md)).

### 2 — Embeddings + incident matching ✅
Gemini `gemini-embedding-001` at 1536 dimensions, pgvector, single-linkage
matching at threshold 0.84 (0.75 until 2026-10-21). Runs asynchronously after commit, with a circuit
breaker, a naive fallback (`degraded`) and a reconcile sweep, so a citizen never
waits on an embedding call. Every decision is logged in `incident_match_log`.
Contract: [matching-contract.md](matching-contract.md).

### 3 — Explainable priority engine ✅
Four terms: volume, growth, age, spread (weights 40/30/20/10; bands
3.0/5.5/7.5). Reasons are stored with the score. A 5-minute
`PriorityRefreshJob` keeps age current. The weights are hand-set and frozen until
Phase 8 ([priority-evaluation.md](priority-evaluation.md)).

### 4 — Officer dashboard + map ✅
Incident queue, detail panel with member complaints, status changes, Leaflet
map coloured by priority.

### 5 — Trends + analytics ✅
Delivered inside the Explorer: trends, distributions and resolution metrics
(Phase 6).

### S — Stabilisation + data foundation ✅
- Category registry with aliases (V11, V14): normalised on write; unknown
  categories are rejected.
- Real ward polygons (V13); overlap validation (V20); `dataset_validations` (V17).
- Ingestion gate `/internal/ingest`: raw first, idempotent, one transaction per
  record (V15).
- Separate `reported_at` and `created_at` (V16); `resolved_at` (V18);
  per-category default department (V19); incident report span (V21).
- Domain rules decided: `CLOSED` = no fix, excluded from resolution metrics;
  time to resolve starts at the first report
  ([data-quality-rules.md](data-quality-rules.md)).
- The September 2026 audit: all 16 issues dispositioned (table at the end).

### 6 — Explorer ✅
One shared filter state (ward, category, status, band, "reported within")
across three views under `/dashboard`:
- **Incidents:** paged and searched server-side.
- **Wards:** choropleth plus a sortable table, and a ward detail page.
- **Categories:** sparklines and resolution figures, and a category detail page.

Backend: 4 new and 5 extended endpoints, listed in api-contract.md "Endpoint
classification". Verified at 1440/1024/390 px.

### 7 — Matching evaluation ✅
Report: [matching-evaluation.md](matching-evaluation.md). The harness:
`npm run eval:matching` and `npm run eval:production-joins`.
- At 0.75 the matcher merges different problems in the same ward and category.
- A **500 m distance check** on the matched member is built
  (`MATCH_MAX_SIBLING_DISTANCE_M`). It removed 6 of 22 wrong joins and lost none
  of 27 right ones.
- **Decided 2026-10-21: threshold 0.84.** Best mean F2 across both datasets.
  The recall it seemed to cost came from seed hotspots that mixed different
  faults. With one real problem per hotspot, 36 of 36 hotspots form one
  incident each at 0.84. Live on Railway; reasoning in matching-evaluation.md §4.

### Infrastructure ✅ (2026-10-21)
- Moved off Neon after its free quota ran out. The database now runs as a Railway
  service built from `infra/postgres` ([railway-database.md](railway-database.md)).
- Tests can no longer migrate production. Local development uses a Docker
  database.
- Production was reseeded with demo data.

---

## Left to do

### 12 — Officer Copilot 🟡 (owner: Manya)
An officer asks "what's urgent in Ward 48?" and gets a short answer built only
from CivicPulse data, with every claim citing its incident, ward or category.
When the data can't answer, it says so plainly instead of guessing.

```text
Browser ─POST /api/core/copilot/query─> Spring (officer session, 30/hr limit,
   builds the grounding packet from the Explorer's own services)
   ─POST /internal/copilot/answer─> Node ─> model (COPILOT_PROVIDER)
   <─ answer + citations; Spring drops any citation not in the packet
```

**Answer rules** (the prompt and Spring's checks enforce them):
1. Answer only from the packet.
2. Every factual sentence cites a packet id.
3. If the packet cannot answer, say so and name what is missing.
4. Explain priority with the stored reasons only.
5. At most 150 words.

Resident complaint text is not sent to the model.

| # | Milestone | State |
|---|---|---|
| 12.1 | Contract + service-boundary decision | ✅ api-contract.md "Copilot"; service-boundaries.md §5 |
| 12.2 | Node `/internal/copilot/answer`, swappable provider (none / ollama / gemini / anthropic) | ✅ |
| 12.3 | Spring `/copilot/query`: packet builder, citation check, per-officer limit | ✅ |
| 12.4 | **Copilot panel in the Explorer** | ⬜ |
| 12.5 | **Golden-question eval** (~20 questions, some unanswerable) | ⬜ |

**Live now on Gemini** (`gemini-3.8-flash`) in production and locally. Verified
end to end on 2026-10-21. Ollama (`qwen2.5:7b`) is the free option for
development.

**12.4 — the panel.** A drawer on the Explorer pages:
- A question box, and the answer.
- Each `sources` entry is a chip that links to its target: `incident` opens it in
  the queue, `ward` goes to `/dashboard/wards/:id`, `category` to
  `/dashboard/categories/:code`, and `metric` has no link.
- Send the shared filters as the scope.
- States to design:
  - loading (answers take about 8 s);
  - `ungrounded: true` (show a warning, not the answer as fact);
  - `answerable: false`;
  - `refused`;
  - 503 `COPILOT_UNAVAILABLE`;
  - 429 with `Retry-After`.

Devansh's design system applies.

**12.5 — the eval.** About 20 questions against a seeded local database,
including unanswerable ones (weather, an out-of-scope ward, "who is the mayor").
Check automatically that:
- every cited id is in the packet;
- every number in the answer appears in the packet;
- unanswerable questions come back `answerable: false`.

Run it per provider. The comparison goes in the paper.

### 8 — Priority calibration ⬜
The weights are frozen until an officer has ranked a real queue
([priority-evaluation.md](priority-evaluation.md)).

| # | Milestone |
|---|---|
| 8.1 | Export the active queue as a ranking sheet (CSV). Show reasons and counts, not the score |
| 8.2 | Import and metrics: Kendall τ and precision@10 between the formula and the officer, with per-term attribution of disagreements |
| 8.3 | **Officer session (people):** ≥ 30 incidents ranked by one officer, ideally two |
| 8.4 | Weight/band proposal, adopted only if τ improves on held-out incidents |

### 9 — Hotspots ✅ (2026-10-21)
A hotspot is **≥ 3 active incidents of one category within 500 m of each other**
(DBSCAN over incident centres, done by PostGIS), each reported in the window
(default: the last 30 days). Rule-based; no ML.

| # | Milestone | State |
|---|---|---|
| 9.1 | Definition, written with its reasons | ✅ api-contract.md "GET /dashboard/hotspots" |
| 9.2 | `GET /dashboard/hotspots` (category, ward, window filters) | ✅ DB-backed tests: cluster, too-small pair, far incident, resolved incident, ward/category/window filters |
| 9.3 | Rings on the Ward Explorer map plus a hotspot list; every member opens in the queue | ✅ checked at 1440 and 390 px |

The demo seed adds three clusters of different problems chosen to stay
separate incidents at 0.84, so the live site has hotspots to show.

### 10 — Operational intelligence ⬜ (after 9)
One "needs attention" strip that turns counts into statements, each opening its
incidents:

| Signal | Rule |
|---|---|
| High priority | Active, high or critical band |
| Worsening | 2 or more new reports in 72 h, or score up ≥ 1.0 since yesterday |
| Concentrated | In a Phase 9 hotspot |
| Category spike | Last 7 days ≥ 2× the prior 4-week weekly mean, and ≥ 5 reports |

| # | Milestone |
|---|---|
| 10.1 | `GET /dashboard/attention`, returning signals with incident ids |
| 10.2 | "Needs attention" strip on the Incidents view |

### 11 — Decision support ⬜ (after 8, 10 and 12)
Recommendations an officer can check, never actions taken on their behalf.

| # | Milestone |
|---|---|
| 11.1 | Rule-based recommended action per incident (dispatch / consolidate / monitor / escalate / verify-and-close), showing which rule fired |
| 11.2 | Copilot-written note grounded in the incident and its rule |
| 11.3 | Officer accept/override logged. The override rate is the success metric |

### 13 — Paper + demo 🟡
| Section | Source | Ready |
|---|---|---|
| System: architecture, data quality, ingestion gate | service-boundaries.md, data-quality-rules.md | ✅ now |
| Matching: method, labelled sets, P/R/F1/F2, ablation, distance check | matching-evaluation.md, `backend-node/eval/results/` | ✅ now |
| Copilot: grounding design and eval results | 12.5 | after 12.5 |
| Priority: explainability and calibration vs officer | priority-evaluation.md + 8.2 | after 8.3 |
| Demo: rehearsed run and a backup video | — | ⬜ |

### Decisions and reviews (people, not code)
| Item | Owner |
|---|---|
| Review the 30 hand labels in `backend-node/eval/production-joins-labelled.json` | an officer / Devansh |
| Review the Explorer UI and the `--density-*` tokens | Devansh |
| Clear and reseed the live demo data: it predates 0.84 and the one-problem-per-hotspot seed | team (bulk delete on production) |

### Backlog — deliberately deferred
- **Photo upload.** Contract approved, not built.
- **Officer merge / unlink of complaints.** The manual override for false
  merges; the backend hook exists, there is no endpoint or UI.
- **"Why are these grouped".** Explain a match in operator language (for example
  "40 m away, same problem"), not cosine scores.
- **`timestamptz` migration.** Touches every timestamp column
  ([data-quality-rules.md](data-quality-rules.md) §2).
- **First real data feed** through `/internal/ingest`. Two open points:
  - Decide whether a changed source timestamp counts as a new record.
  - Re-run the matching evaluation on real complaints.
- **Code-mixed text** (Kannada/Hindi–English). Evaluate transliteration before
  embedding, using the existing harness.

---

## Rules that still apply
- Every number shown to an operator is traceable to a column written on purpose.
- Never point a local `.env` at production: Spring startup and `./mvnw test`
  migrate `DB_URL`.
- No ML before evaluation data; no LLM where a deterministic rule is safer.
- Done means all of these:
  - contract written;
  - backend and tests;
  - frontend with empty and error states;
  - verified against real data;
  - docs updated.

---

## Old names → this document
Code comments and older docs use these labels.

| Old label | Now |
|---|---|
| Pre-midsem Phases 0–5 | Phases 0–5 |
| Pre-midsem Phase 6 "Officer Copilot", next-phases "Phase A" | Phase 12 |
| Pre-midsem Phase 7 "Predictive hotspots", post-midsem Phase 9, next-phases "C" | Phase 9 |
| Pre-midsem **Phase 8 "Evaluation + paper"** (the "Phase 8 evaluation" in code comments) | Phases 7 (matching), 8 (priority), 13 (paper) |
| Post-midsem Phases 1–2 (data foundation, API contract freeze) | Phase S |
| Post-midsem Phases 3–6 (Explorer), "roadmap §7–§16" | Phase 6 |
| Post-midsem Phase 7 / §17 | Phase 7 |
| Post-midsem Phase 8, next-phases "B" | Phase 8 |
| Post-midsem Phase 10, next-phases "D" | Phase 10 |
| Post-midsem "decision support" / Phase 11, next-phases "E" | Phase 11 |
| Next-phases "F" | Phase 13 |

### September 2026 audit — ISSUE IDs
Several code comments cite these.

| ID | Issue | Outcome |
|---|---|---|
| ISSUE-01 | Requirements docs did not match the matching rules | Resolved (documented) |
| ISSUE-02 | Copilot / hotspot stubs presented as complete | Resolved |
| ISSUE-03 | Category governance missing | Resolved: registry + aliases |
| ISSUE-04 | Exact category matching too strict | Resolved via ISSUE-03 |
| ISSUE-05 | Ward data was placeholder | Resolved: 243 BBMP wards |
| ISSUE-06 | Nearest-centroid ward assignment | Resolved: PostGIS containment |
| ISSUE-07 | Priority scores go stale | Resolved: `PriorityRefreshJob` |
| ISSUE-08 | Priority not calibrated | Measured; decision recorded, so the weights stay frozen → **Phase 8** |
| ISSUE-09 | O(N²) geographic spread | Measured; deliberately not optimised |
| ISSUE-10 | No ingestion validation gate | Resolved: `/internal/ingest` |
| ISSUE-11 | External/internal ward ID governance | Resolved: crosswalk view |
| ISSUE-12 | Frontend category list hardcoded | Resolved: served by the API |
| ISSUE-13 | Matcher lacks spatial/temporal bounds | Resolved (documented); 500 m check added in Phase 7 |
| ISSUE-14 | Single-linkage chaining | Demonstrated; kept as an explicit decision |
| ISSUE-15 | Seed data insufficient | Mitigated (seed script; Phase 7 eval set) |
| ISSUE-16 | Photo upload unresolved | Deferred, contract approved → backlog |
