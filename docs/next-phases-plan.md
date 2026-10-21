# Next Phases — Implementation Plan

**Written:** 2026-10-20. **Extends:** [post-midsem-roadmap.md](post-midsem-roadmap.md)
(phases 1–7 are done; see its progress notes and
[matching-evaluation.md](matching-evaluation.md)).

This plan covers everything after the Phase 7 checkpoint: what gets built, in
what order, what each piece must do to count as done, and which steps need a
person rather than code.

---

## 0. Before any new phase — operational follow-ups

| # | Action | Owner | Why it matters |
|---|---|---|---|
| O1 | ✅ Push `e6d969c`, `673e9fe` | team | Done 2026-10-21 |
| O2 | ✅ Deploy backend-spring **and** frontend together | team | Done 2026-10-21 — Explorer and `/copilot/query` verified live |
| O3 | Decide `MATCH_SIMILARITY_THRESHOLD` (0.75 vs 0.84) | team | Railway variable; evidence in matching-evaluation.md §4 |
| O4 | Review the 30 hand labels in `backend-node/eval/production-joins-labelled.json` | an officer / Devansh | Single labeller today |
| O5 | Review the Explorer UI and the new `--density-*` tokens | Devansh | It is his design system |
| O6 | ✅ Choose `COPILOT_PROVIDER` | team | `gemini` (`gemini-3.8-flash`) on Railway and locally, 2026-10-21; Manya may revisit |
| O7 | ✅ Move the database to Railway Postgres | team | Done 2026-10-21 and reseeded with demo data; [railway-database.md](railway-database.md) |

---

## 1. Order and dependencies

```text
A. Officer Copilot (AI) ─────────────────────────────┐
   needs: Explorer endpoints (done), an API key (O6)   │
                                                       ├──> E. Decision support
B. Priority calibration tooling ──> B'. officer ranks ─┤
   needs: nothing          needs: an officer's morning │
                                                       │
C. Hotspots (written definition first) ──> D. Operational intelligence
   needs: matching measured (done)          needs: C

F. Paper — drafts in parallel from now; priority half waits on B'
```

Build order: **A → B → C → D → E**, with F alongside. A comes first because it
is the one AI deliverable that is unblocked today (it predicts nothing; it
answers from data that now exists) and the most visible for the paper and the
demo. B's tooling comes second so the officer session can be booked early —
B' is the only step that needs a person's time, and it gates E.

---

## 2. Phase A — Officer Copilot

**Goal.** An officer asks "what's urgent in Ward 48?" and gets a short answer
built only from CivicPulse's own data, with every claim linked to the incident,
ward or category it came from — or a plain "I can't answer that from the
data" instead of a guess.

### Architecture (decided)

```text
Browser ──POST /api/core/copilot/query──> backend-spring  (officer session, role check)
                                            │ builds a grounding packet from its
                                            │ own services (same definitions as the
                                            │ Explorer: active, high priority, windows)
                                            ▼
                    backend-node  POST /internal/copilot/answer  (X-Internal-Token)
                                            │ Claude call: question + packet only
                                            ▼
            { answer, citations[] }  ── Spring re-checks every citation is in
                                        the packet, attaches links, returns
```

Why this shape and not the planned Node-only `POST /copilot/query`:

- **The planned body carried `officer_id` from the browser.** Node has no
  session handling, so that identity was whatever the caller typed. Spring
  already owns sessions and the officer role — the Copilot goes behind them.
- **One definition of every number.** The packet is built by the same Spring
  services that serve the Explorer, so the Copilot cannot disagree with the
  dashboard about what "high priority" or "last 7 days" means.
- **Node stays the intelligence layer** ([service-boundaries.md](service-boundaries.md)):
  it makes the model call and nothing else; it gets no database access for this.

### Grounding packet

A compact JSON document, ~5–15k tokens, scoped by the officer's current
Explorer filters (ward, category, window):

| Section | Source | Cited as |
|---|---|---|
| Scope + data freshness | request + `priorityComputedAt` | — |
| Headline counts | `/dashboard/summary` or `/dashboard/wards/{id}/summary` | `metric:<name>` |
| Top active incidents (≤ 20, by priority) with reasons, status, report span, owner | `GET /incidents` | `incident:<id>` |
| Per-category summary | `/dashboard/categories` | `category:<code>` |
| Per-ward summary (city scope: top 10 by active) | `/dashboard/wards` | `ward:<id>` |
| Reported trend (last 14 days) | `/dashboard/trends` | `metric:trend` |

Resident complaint text is **not** included in the first version — only
incident-level fields. That keeps the packet small and keeps residents' words
out of a third-party request until the team decides otherwise.

### Answer rules (the contract the prompt and the checks enforce)

1. Answer only from the packet. No outside knowledge, no assumptions about
   Bengaluru beyond what the data says.
2. Every factual sentence cites at least one packet id.
3. If the packet cannot answer, say so and name what is missing — never guess.
4. Explain priority with the stored `priorityReasons`, never invent a reason.
5. Short: lead with the answer; ≤ 150 words unless asked for more.

Spring drops any citation that is not in the packet and reports how many it
dropped; an answer with zero valid citations is returned flagged
`ungrounded: true` so the UI can say so.

### Milestones

| # | Deliverable | Done when |
|---|---|---|
| A1 | Contract in `api-contract.md` + service-boundaries decision | endpoints, packet shape, rules, errors written down |
| A2 | Node `POST /internal/copilot/answer` (Claude via `@anthropic-ai/sdk`, structured output, refusal fallback) | unit tests with a mocked client; 503 `COPILOT_UNAVAILABLE` when no key |
| A3 | Spring `POST /copilot/query` + packet builder + citation check + per-officer rate limit | DB-backed tests of the packet; citation filtering tested |
| A4 | Copilot panel in the Explorer, scoped by the shared filters | answer with clickable citations; loading / error / unavailable states; screenshot-verified |
| A5 | Golden-question eval (~20 questions incl. out-of-scope ones) | grounding checks pass: every citation valid, every number in the answer present in the packet, out-of-scope questions refused |

**Model (revised 2026-10-21):** swappable with `COPILOT_PROVIDER` on
backend-node, default `none` (Copilot off):

| Provider | Cost | Use |
|---|---|---|
| `ollama` (default model `qwen2.5:7b`) | free, runs locally | development, demos, the paper's eval |
| `gemini` (`gemini-3.8-flash`) | billed on the project's Google key | deployed use if the team accepts the cost |
| `anthropic` (`claude-opus-5-5`, refusal fallback on) | billed | strongest answers; optional |

Safety does not depend on the choice: Spring's citation check applies to every
provider.

### Status and handoff (2026-10-21) — for Manya

| # | State | Where |
|---|---|---|
| A1 | ✅ Contract and decision written | api-contract.md "Copilot"; service-boundaries.md §5 |
| A2 | ✅ Node: `/internal/copilot/answer`, three providers, tests | `backend-node/src/services/copilot.service.ts`, `copilotProviders.ts` (+ tests) |
| A3 | ✅ Spring: `/copilot/query`, packet builder, citation check, per-officer limit, tests | `backend-spring/.../service/copilot/`, `controller/CopilotController.java` |
| A4 | ⬜ **Next:** the Copilot panel in the Explorer | see below |
| A5 | ⬜ Golden-question eval | see below |

Verified: 229 Spring + 49 Node tests pass against a fresh local database
(`docs/railway-database.md` §7), including the packet builder against real
schema and data.

**Run it locally (free):**

1. Install Ollama, then `ollama pull qwen2.5:7b`.
2. In `backend-node/.env`: `COPILOT_PROVIDER=ollama`.
3. Start backend-spring and backend-node as usual, sign in as an officer, and
   `POST /api/core/copilot/query` with `{"question": "what is most urgent?"}`.

**A4 — the panel.** A drawer or side panel on the Explorer pages: a question
box; the answer; each `sources` entry as a chip that navigates (`incident` →
open it in the queue the way `IncidentList` does; `ward` →
`/dashboard/wards/:targetId`; `category` → `/dashboard/categories/:targetId`;
`metric` → no link). Send the shared filters as the scope (`wardId`,
`category`, `from`/`to` from `reportWindow`). States to design: loading (answers
take seconds), `ungrounded: true` (show a warning, not the answer as fact),
`answerable: false`, `refused`, 503 `COPILOT_UNAVAILABLE` ("Copilot is not set
up on this server"), 429 with `Retry-After`. Devansh's design system applies.

**A5 — the eval.** ~20 questions against a seeded local database (some unanswerable:
weather, a ward outside the scope, "who is the mayor"). Check automatically
that every cited id is in the packet (Spring already counts drops), that every
number in the answer appears in the packet, and that unanswerable questions
come back `answerable: false`. Run it per provider — the comparison is good
paper material.

---

## 3. Phase B — Priority calibration (post-midsem Phase 8)

The formula's weights (40/30/20/10) and bands (3.0/5.5/7.5) are hand-set and
frozen until an officer has ranked a real queue
([priority-evaluation.md](priority-evaluation.md)). Build the tooling so that
session takes one sitting:

| # | Deliverable | Done when |
|---|---|---|
| B1 | Export: the active queue as a ranking sheet (CSV) — incident, reasons, counts, no score shown | officer can rank without seeing the formula's answer |
| B2 | Import + metrics: Kendall τ and precision@10 between formula and officer ranking; per-term attribution of disagreements | script runs on a fake ranking in tests |
| B3 | **Officer session (people)** | ≥ 30 incidents ranked by one officer, ideally two |
| B4 | Weight/band proposal from B2, written into priority-evaluation.md | change only if τ improves on held-out incidents |

---

## 4. Phase C — Hotspots (post-midsem Phase 9)

**Definition first, written before code** (roadmap §20). Proposed:

> A hotspot is a place where at least **3 active incidents of the same
> category** lie within **500 m** of each other and each has a report in the
> last **30 days**.

Rule-based (density clustering over incident centroids, DBSCAN-style with
ε = 500 m, minPts = 3, per category). No ML — 31 incidents cannot train a
model, and the roadmap forbids ML before evaluation data. 500 m matches the
matching evaluation's measured same-problem radius.

| # | Deliverable | Done when |
|---|---|---|
| C1 | Definition ratified by the team (this section) | written in a hotspots doc |
| C2 | `GET /dashboard/hotspots` (category, window filters) — members, centre, radius, ward(s) | DB-backed tests with constructed clusters |
| C3 | Map layer on the Ward Explorer + hotspot list | screenshot-verified |

---

## 5. Phase D — Operational intelligence (post-midsem Phase 10)

One "needs attention" endpoint that turns counts into statements, every one
drilling through to its incidents:

| Signal | Definition (deterministic) |
|---|---|
| High priority | active, band high/critical (exists) |
| Worsening | ≥ 2 new member reports in the last 72 h, or score up ≥ 1.0 since yesterday |
| Concentrated | member of a Phase C hotspot |
| Category spike | category's last-7-day reports ≥ 2× its prior 4-week weekly mean, and ≥ 5 |

| # | Deliverable | Done when |
|---|---|---|
| D1 | `GET /dashboard/attention` — signals with incident ids | tests per signal |
| D2 | "Needs attention" strip on the Incidents view | each item opens its incidents |

---

## 6. Phase E — Decision support (post-midsem Phase 11)

Recommendations an officer can check, never actions taken for them.

| # | Deliverable | Done when |
|---|---|---|
| E1 | Rule-based recommended action per incident (dispatch / consolidate / monitor / escalate / verify-and-close) from signals A–D, with the rule that fired | deterministic, tested |
| E2 | Copilot-written `aiRecommendation`: a short note grounded in the incident and its rule | uses Phase A's grounding + citation checks |
| E3 | Officer accept / override logged | override rate is the metric that says whether E1 helps |

Waits on B′: recommending by priority before priority is calibrated would
automate an uncalibrated judgement.

---

## 7. Phase F — Paper

| Section | Source | Ready |
|---|---|---|
| Matching: method, labelled sets, P/R/F1/F2, ablation, distance finding | matching-evaluation.md, `eval/results/` | now |
| Priority: explainability (stored reasons), calibration vs officer ranking | priority-evaluation.md + B2 output | after B′ |
| Copilot: grounding design and golden-question results | A5 output | after A5 |
| System: architecture, data quality rules, ingestion gate | service-boundaries.md, data-quality-rules.md | now |

---

## 8. Cross-cutting

- **Re-run the matching evaluation** when real resident complaints exist
  (`npm run eval:matching`, `npm run eval:production-joins` after labelling a
  fresh sample). The threshold decision (O3) should be revisited then.
- **`timestamptz` migration** — the one schema change worth doing deliberately
  ([data-quality-rules.md](data-quality-rules.md) §2).
- **Code-mixed text** — evaluate transliteration before embedding with the
  existing harness before changing any stored vector.

## 9. Definition of done (every milestone)

Contract written → backend + tests → frontend + empty/error states →
verified against production data (read-only) → screenshots where there is UI →
docs updated → committed. Unchanged from roadmap §34, with the one addition this
project has earned: anything that touches the database is checked against the
live schema, because tests migrate production.
