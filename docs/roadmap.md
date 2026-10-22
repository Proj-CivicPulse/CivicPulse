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
| 8 | Priority calibration | ✅ Closed 2026-10-22: age 30 d applied; category weight decided against |
| 9 | Hotspots | ✅ Done (2026-10-21) |
| 10 | Operational intelligence ("needs attention") | ✅ Done (2026-10-21) |
| 11 | Decision support | ✅ Done (2026-10-21); the Copilot note was cut |
| 12 | Officer Copilot | 🟡 Backend live; panel + eval left (Manya) |
| 13 | Paper + demo | 🟡 Matching and system sections ready |

**All code-only work is done, and Phase 8 is closed.** Two things are left:

1. **12.4–12.5:** the Copilot panel and its question set (Manya).
2. **13:** the paper and a rehearsed demo, after the backends are redeployed.

### Deployment status (2026-10-21)
- **GitHub:** `main` at `4cd2721`, with every phase through 11.
- **Frontend (Vercel):** live with the new code.
- **Backends (Railway):** still on the build before Phases 9–11. Railway had a
  platform outage the same day, with builds paused in every region, so the
  push did not deploy. Until the backends are redeployed, the hotspot list,
  the "needs attention" strip and the suggested next step fail quietly on the
  live site; everything else works. Once Railway builds again, run from the
  repo:

  ```bash
  railway redeploy -s backend-spring --from-source -y
  railway redeploy -s backend-node --from-source -y
  ```

  The Spring deploy applies migration V22, which adds one table and changes
  nothing that exists.

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
3.0/5.5/7.5; age saturates at 30 days, 14 until 2026-10-22). Reasons are stored with the score. A 5-minute
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

### 10 — Operational intelligence ✅ (2026-10-21)
A "needs attention" strip over the incident queue: four statements, each
opening the incidents behind it, narrowed by the queue's ward and category.

| Signal | Rule |
|---|---|
| High priority | Active, high or critical band |
| Getting worse | 2 or more new reports in 72 h |
| In a hotspot | Member of a Phase 9 hotspot |
| Category spiking | Last 7 days ≥ 2× the prior 4-week weekly mean, and ≥ 5 reports |

| # | Milestone | State |
|---|---|---|
| 10.1 | `GET /dashboard/attention`, signals with their incidents | ✅ DB-backed tests with a fixed clock, one per rule plus filters |
| 10.2 | Strip on the Incidents view; each item opens in the queue | ✅ checked at 1440 and 390 px |

"Score up ≥ 1.0 since yesterday" was dropped: scores are not historised.

### 11 — Decision support ✅ (2026-10-21)
A suggested next step in the incident panel, with the rule that fired and the
facts it read. The officer accepts or overrides it, and the decision is
logged. These are recommendations an officer can check, never actions taken
on their behalf.

| # | Milestone | State |
|---|---|---|
| 11.1 | Rule-based action per incident: escalate, then dispatch, then verify-and-close, then consolidate, then monitor (first match wins), naming the rule | ✅ unit tests per rule and their order; DB-backed service tests |
| 11.2 | Copilot-written note | ✂️ cut to finish sooner. The panel still shows `aiRecommendation` if one is ever written |
| 11.3 | Accept/override log (V22), and `GET /dashboard/recommendations` with the override rate per action | ✅ tested; checked over HTTP and at 1440/390 px |

The rules use the calibrated priority bands once Phase 8.3–8.4 has run. Until
then they read the shipped formula, as everything else does.

### Infrastructure ✅ (2026-10-21)
- Moved off Neon after its free quota ran out. The database now runs as a Railway
  service built from `infra/postgres` ([railway-database.md](railway-database.md)).
- Tests can no longer migrate production. Local development uses a Docker
  database.
- Production was reseeded with demo data at 0.84: one real problem per hotspot
  (36 hotspots, 36 incidents), plus 3 clusters so hotspots have something to
  show.

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

The project's design system applies: tokens in `tokens.css` and the shared components in `components/ui`.

**12.5 — the eval.** About 20 questions against a seeded local database,
including unanswerable ones (weather, an out-of-scope ward, "who is the mayor").
Check automatically that:
- every cited id is in the packet;
- every number in the answer appears in the packet;
- unanswerable questions come back `answerable: false`.

Run it per provider. The comparison goes in the paper.

### 8 — Priority calibration ✅ (closed 2026-10-22)
The weights stay frozen until an officer has ranked a real queue. The steps
are in [priority-evaluation.md](priority-evaluation.md), "Phase 8 — the
officer session".

| # | Milestone | State |
|---|---|---|
| 8.1 | `GET /dashboard/ranking-sheet`: the active queue as a CSV with the formula's inputs, no score, in a scrambled order; `?withScores=true` is the analyst's key | ✅ DB-backed tests with hand-computed scores |
| 8.2 | `npm run eval:priority`: Kendall τ-b, precision@10, each term alone, the age variants on two halves, officer vs officer. It first checks its copy of the formula against the key | ✅ unit tests on a stand-in officer; end-to-end on local data (the copy reproduces 45/45 scores) |
| 8.3 | Ranking session. **Proxy run done 2026-10-22:** Gemini ranked 45 incidents three times, blind to the formula ([priority-calibration-proxy.md](priority-calibration-proxy.md)). The formula beats the naive baseline in every run. **A human ranking is still to come** | 🟡 |
| 8.4 | **Applied 2026-10-22: age saturates at 30 d** (it beat 14 d on both halves in 3/3 runs). **Category severity: decided against** on published evidence, because the hazard belongs to the problem, not its category. A life-safety flag is in the backlog | ✅ |

### 13 — Paper + demo 🟡
| Section | Source | Ready |
|---|---|---|
| System: architecture, data quality, ingestion gate | service-boundaries.md, data-quality-rules.md | ✅ now |
| Matching: method, labelled sets, P/R/F1/F2, ablation, distance check | matching-evaluation.md, `backend-node/eval/results/` | ✅ now |
| Copilot: grounding design and eval results | 12.5 | after 12.5 |
| Priority: explainability, formula vs naive baseline, proxy calibration | priority-evaluation.md, priority-calibration-proxy.md | ✅ now (human ranking would strengthen it) |
| Demo: rehearsed run and a backup video | — | ⬜ |

### Decisions and reviews (people, not code)
| Item | Owner |
|---|---|
| Review the 30 hand labels in `backend-node/eval/production-joins-labelled.json` | team |
| UI review: the Explorer and `--density-*` tokens, the hotspot rings and list, the attention strip and the suggestion box | team |
| Redeploy both backends once Railway builds again (commands in "Deployment status") | team |

### Backlog — deliberately deferred
- **Life-safety flag on incidents** (from Phase 8). Flags an open drain,
  exposed wiring, sewage overflow or a collapsed road, set by an officer or
  from report text. It feeds decision support (escalate), not the score.
  Needs a sub-type taxonomy or text classification
  ([priority-calibration-proxy.md](priority-calibration-proxy.md)).
- **Optional human ranking** of the calibration sheet, to confirm the 30-day
  age change; `npm run eval:priority` scores it in one command.
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
