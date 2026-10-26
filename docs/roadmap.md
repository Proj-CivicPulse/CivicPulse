# CivicPulse — Roadmap and Status

**The single source for what is done and what is left.** Updated 2026-10-25: **project complete.**
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
| 12 | Officer Copilot | ✅ On every Explorer page; sees hotspots and life-safety flags; eval 22/22 (2026-10-25) |
| 13 | Paper | ✅ Only if required: every section's results exist. Final deck and speaker script in `presentation/` |
| B | Backlog items delivered 2026-10-22/25 | ✅ Life-safety flag, officer merge/unlink, "why grouped", `timestamptz`, label review, photo upload, upstream-edit rule |

**Every phase, evaluation and loose end is done.** Nothing is open. Only the
backlog below remains, and it is deliberately deferred. If a paper is
required, every section's results are in the docs listed under Phase 13.

### Deployment status (2026-10-25)
- **Photo upload is built but off in production.** Status 2026-10-26: the
  bucket exists (`photos`, region `sin`) and Vercel already has
  `VITE_PHOTO_STORAGE_ORIGIN=https://photos-7zlmvtzrgsgqyyls-s.t3.storageapi.dev`
  (live CSP verified). **Only step 2 is left.** To switch it on:
  1. ~~In Railway, add a **Bucket** to the project.~~ Done.
  2. On backend-spring, set these from the bucket's variables (Railway
     variable references): `PHOTO_STORAGE_ENDPOINT=${{Bucket.ENDPOINT}}`,
     `PHOTO_STORAGE_BUCKET=${{Bucket.BUCKET}}`,
     `PHOTO_STORAGE_ACCESS_KEY=${{Bucket.ACCESS_KEY_ID}}`,
     `PHOTO_STORAGE_SECRET_KEY=${{Bucket.SECRET_ACCESS_KEY}}`,
     `PHOTO_STORAGE_REGION=${{Bucket.REGION}}`; add
     `PHOTO_STORAGE_PATH_STYLE=true` only if its Credentials tab says
     path-style.
  3. ~~On Vercel, set `VITE_PHOTO_STORAGE_ORIGIN`~~ Done. It is the bucket's origin:
     `https://<BUCKET>.t3.storageapi.dev` for a virtual-hosted bucket (the
     host of `ENDPOINT` with the bucket name in front), and redeploy.
  4. **CORS needs no manual step:** on startup Spring sets the bucket's CORS
     rule itself (`PutBucketCors`) to allow PUT/GET from `FRONTEND_ORIGIN`,
     and logs `Photo storage: CORS allows PUT/GET from [...]`. If it logs
     "could not set the bucket's CORS rule" instead, browser uploads will fail
     until the bucket allows PUT from the Vercel origin.
  Until then the confirmation screen says photo upload is unavailable.
- **Railway** auto-deploys `main` on every push. Production applied V22–V25
  on 2026-10-24 and V26 (photo key) on 2026-10-25, each cleanly; it runs the
  Copilot packet and panel changes and the photo code with storage off.
- **Vercel** builds the frontend from `main` the same way.
- **Production data** was reseeded on 2026-10-25, after a full `pg_dump` to
  `deploy-secrets/backups/prod-before-reseed-2026-10-25.sql` (gitignored).
  249 demo complaints became 45 incidents, all matched semantically; user
  accounts, wards and categories were kept. To reseed again: back up, clear
  `complaints`, `incidents`, `incident_match_log` and
  `recommendation_feedback` in one transaction, then run
  `SEED_API=<spring url> SEED_DB=railway SEED_INTERNAL_TOKEN=… node backend-spring/scripts/seed-dev-data.mjs`.

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
complaints". One photo per report since 2026-10-25: uploaded straight to an
S3-compatible bucket, re-encoded without its metadata
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

## Closing phases (all complete)

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
| 12.4 | Copilot panel | ✅ merged 2026-10-24 (PR #2), on the Incidents page; polish left, see below |
| 12.5 | Golden-question eval | ✅ 2026-10-24: 21/22 pass on Gemini; results below |

**Live now on Gemini** (`gemini-3.8-flash`) in production and locally. Verified
end to end on 2026-10-21. Ollama (`qwen2.5:7b`) is the free option for
development.

**12.4 — the panel, as merged.** "Ask Copilot" on the incident queue opens the
panel. It sends the queue's ward, category and window as the scope, links
each source, and handles every state below, including 429 with its
`Retry-After` wait. Finished 2026-10-25 (`0106b84`):
- **Styles.** The panel is a CSS module on `tokens.css` with the shared
  `Button` and `Field`, replacing 33 hard-coded colours.
- **Every Explorer page.** `CopilotSection` puts the same panel on the Wards
  and Categories pages, scoped to each page's filters. A cited incident opens
  in the queue with its filters cleared; a hotspot opens the Wards page.

The original plan for the panel, kept for reference. A drawer on the Explorer pages:
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

**12.5 — the eval, run 2026-10-24.** `npm run eval:copilot` asks
22 questions in `backend-node/eval/copilot-questions.json` through Spring as an
officer, the same path the panel uses, and scores each answer against the
packet the model saw. The packet is captured by backend-node only when
`COPILOT_EVAL_CAPTURE` is set, which it never is when deployed. Report:
`backend-node/eval/results/copilot-2026-10-24/report.md`.

| Check (Gemini `gemini-3.8-flash`, local seed) | Result |
|---|---|
| Questions passing every check | 21/22 (95%) |
| Answerable questions answered | 12/12 |
| Expected fact stated (counts, top ward, top incident…) | 10/10 |
| Unanswerable questions declined, citing nothing | 8/9 (weather, mayor, budget, a resident's phone number, a prompt injection, another ward, …) |
| Citations not in the packet | 0 of 37 |
| Numbers not in the packet | 0 of 81 |
| Median latency | 4.3 s |

**The one failure was a packet gap, not a hallucination.** Asked "where are
the hotspots?", the Copilot named the busiest wards. Every figure it gave was
true, but a CivicPulse hotspot is 3+ incidents of one category within 500 m
(Phase 9), and the packet held no hotspots and no life-safety flags.

**Fixed and re-run 2026-10-25** (`8023201`). The packet now holds the scope's
hotspots, each incident's life-safety flag and the "needs attention" signals,
with their definitions. The hotspot and life-safety questions (h1, h2) became
answerable and must state the right count. Report:
`backend-node/eval/results/copilot-2026-10-25/report.md`.

| Check, re-run | Result |
|---|---|
| Questions passing every check | **22/22** |
| Answerable questions answered | 14/14 |
| Expected fact stated (now including the hotspot and life-safety counts) | 12/12 |
| Unanswerable questions declined, citing nothing | 7/7 |
| Citations not in the packet | 0 of 38 |
| Numbers not in the packet | 0 of 81 |
| Median latency | 5.1 s |

Both runs are on Gemini; running the same set on Ollama or Claude would be
the provider comparison, if a paper wants one.

### 8 — Priority calibration ✅ (closed 2026-10-22)
The weights stay frozen until an officer has ranked a real queue. The steps
are in [priority-evaluation.md](priority-evaluation.md), "Phase 8 — the
officer session".

| # | Milestone | State |
|---|---|---|
| 8.1 | `GET /dashboard/ranking-sheet`: the active queue as a CSV with the formula's inputs, no score, in a scrambled order; `?withScores=true` is the analyst's key | ✅ DB-backed tests with hand-computed scores |
| 8.2 | `npm run eval:priority`: Kendall τ-b, precision@10, each term alone, the age variants on two halves, officer vs officer. It first checks its copy of the formula against the key | ✅ unit tests on a stand-in officer; end-to-end on local data (the copy reproduces 45/45 scores) |
| 8.3 | Ranking session. **Proxy run done 2026-10-22:** Gemini ranked 45 incidents three times, blind to the formula ([priority-calibration-proxy.md](priority-calibration-proxy.md)). The formula beats the naive baseline in every run. **A human ranking is still to come** | 🟡 |
| 8.4 | **Applied 2026-10-22: age saturates at 30 d** (it beat 14 d on both halves in 3/3 runs). **Category severity: decided against** on published evidence, because the hazard belongs to the problem, not its category. The life-safety flag that replaces it was delivered 2026-10-22 (below) | ✅ |

### 13 — Paper ✅ (only if required)
| Section | Source | Ready |
|---|---|---|
| System: architecture, data quality, ingestion gate | service-boundaries.md, data-quality-rules.md | ✅ now |
| Matching: method, labelled sets, P/R/F1/F2, ablation, distance check | matching-evaluation.md, `backend-node/eval/results/` | ✅ now |
| Copilot: grounding design and eval results | api-contract.md "Copilot", `backend-node/eval/results/copilot-2026-10-2{4,5}/` | ✅ now (Gemini only) |
| Priority: explainability, formula vs naive baseline, proxy calibration | priority-evaluation.md, priority-calibration-proxy.md | ✅ now (human ranking would strengthen it) |

### Decisions and reviews (people, not code)
| Item | Owner |
|---|---|
| ~~Review the 30 hand labels~~ Done 2026-10-22 (`3725294`): a blind second labeller agreed 63% (κ 0.45) with no same/different flips; 3 labels moved to ambiguous; 0.84 + 500 m still wins | — |
| ~~Reseed the live demo data~~ Done 2026-10-25, after a full backup (see "Deployment status") | — |
| ~~Redeploy both backends~~ Done 2026-10-24: Railway auto-deploys `main` | — |

### UI review ✅ (2026-10-22)
Two passes by Gemini (`gemini-3.8-flash`) on screenshots of the new screens,
with every finding checked against the code before acting. Fixed:
- **Accept / Override** read as if it acted. It is now **Agree / Disagree**,
  with the note "only recorded to check the rules" kept above the controls.
  The form uses the shared `Field` components.
- **No way to change an incident** existed anywhere in the UI. Added **Update
  incident** (status and department) to the panel. "Resolved — fixed" and
  "Closed — ended without a fix" are spelled out.
- **"Open for 21 days" beside "Age 19 days".** Priority age now runs from the
  first report, as everything else does.
- **Hotspots invisible at city zoom.** They now have a fixed-size marker plus
  the true-extent ring, and a legend.
- **Attention cards** gained an open/closed chevron, and the list closes when
  an incident opens, so the queue keeps its room.

Checked and not changed:
- the mobile "overlap" (a full-page screenshot of a fixed sheet);
- the phone hotspot table, which scrolls sideways like the ward table;
- the phone incident queue: same, and it predates this work.

### Delivered from the backlog ✅ (2026-10-22)
- **Life-safety flag** (`d3c8fc0`, V24). Open drains and manholes, exposed
  wiring, sewage overflow, collapsed roads and fallen trees or poles are
  detected from report text, quoting the words that raised the flag; the
  officer's yes or no is final. It is never part of the score: it fires a
  new first decision rule (escalate) and a new "needs attention" signal, and
  shows as a chip in the queue and the panel.
- **Officer merge and unlink** (`1d52b22`, V25). Merge folds one active
  incident into another in the same ward and category; unlink splits a report
  into its own incident. Both are logged with the officer and a reason, and
  the reconcile sweep never undoes them.
- **"Why are these grouped"** (`1d52b22`). Each report in the panel says why
  it belongs, in distance and words, never as a similarity score.
- **`timestamptz`** (`9f5d411`, V23). All 36 timestamp columns, converted in
  place, and every pooled session runs in UTC.
- **Landing page** (`7a7e63f`): the ward strip shows only the ten wards with
  the most open incidents.
- **Photo upload** (2026-10-25, V26). One photo per report, attached after
  submitting: the browser PUTs straight to an S3-compatible bucket (Railway
  Bucket in production, SeaweedFS locally) and the server re-encodes it,
  which strips GPS and device metadata, plus a thumbnail for the officer's
  panel. An anonymous submitter proves the report is theirs with a signed,
  2-hour token rather than the guessable reference number. **Off until a
  bucket is configured** (see "Deployment status").
- **Upstream edits to imported records** (2026-10-25). Decided: a record's
  identity is `(source, source_record_id)`, so a changed source timestamp is
  the same report corrected, never a new one. The incident is recomputed; if
  the location, ward, category or text changed, the stale embedding is
  dropped and the report is re-matched.

### Backlog — deliberately deferred
- **Optional human ranking** of the calibration sheet, to confirm the 30-day
  age change; `npm run eval:priority` scores it in one command. Needs a
  person, ideally a ward officer; no code is missing.
- **First real data feed** through `/internal/ingest`. The gate, its
  timestamp rule and its update path are done; what is missing is a real
  feed that carries complaint **text** and coordinates. When one exists:
  ingest it, label a sample, and re-run `npm run eval:production-joins` on
  real complaints.
- **Code-mixed text** (Kannada/Hindi–English). **Evaluated 2026-10-25**
  ([matching-evaluation.md](matching-evaluation.md) §4a): rewriting every
  report as English before embedding lifts grouping F2 at 0.84 from 0.852 to
  0.909 (recall 94% → 99%, precision 62% → 68%). Not switched on until real
  code-mixed complaints confirm it, because the test texts were authored; the
  change then is one rewrite call in the embedding step plus a re-embed.

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
