# Priority Calibration — Proxy Run

**Status:** 2026-10-22. Phase 8.3 run with a **proxy ranker**, not an officer.
Raw files and the full report:
`backend-node/eval/results/priority-proxy-2026-10-22/`.

**In one paragraph.** A language model (Gemini `gemini-3.8-flash`) never saw the
formula, the scores or the key. It ranked the 45 active demo incidents three
times from the officer's ranking sheet. Its three rankings agree with each other
at τ-b 0.74–0.88, which is the ceiling here. The shipped formula agrees with it
at 0.32–0.42, and beats the naive "most reports first" baseline in every run.
One variant beats the shipped formula on **both** halves in **all three** runs:
**age saturating at 30 days instead of 14**. That is the change
[priority-evaluation.md](priority-evaluation.md) had already named as the first
to try. **Applied 2026-10-22** at the team's decision to accept the proxy
evidence (see "Decision").

---

## Method

| | |
|---|---|
| Incidents | 45 active incidents from the demo seed (the generator behind the live site), local database |
| Sheet | `GET /dashboard/ranking-sheet`: category, ward, place, reports, reports in 24 h and 7 d, days open, spread. No score, no band, no reasons |
| Key | `?withScores=true`, exported in the same minute |
| Ranker | `npm run eval:priority-proxy`: Gemini at temperature 0, briefed as a BBMP ward engineer; no formula, scores or key. Each run sees the rows in a different seeded order under neutral labels (R1…R45), so neither position nor id carries signal |
| Runs | 3 |
| Scoring | `npm run eval:priority`. Before evaluating, its copy of the formula reproduced 45/45 key scores exactly |

## Results

Kendall τ-b against each proxy run (higher = closer to the ranker):

| | Run 1 | Run 2 | Run 3 |
|---|---:|---:|---:|
| **Formula as shipped** | **0.423** | **0.366** | **0.320** |
| Reports only (naive baseline) | 0.395 | 0.321 | 0.268 |
| Age saturates at 30 d | 0.556 | 0.497 | 0.430 |
| Age saturates at 45 d | 0.516 | 0.451 | 0.398 |
| Age weight 10% (moved to volume) | 0.412 | 0.337 | 0.282 |
| precision@10, shipped | 0.60 | 0.60 | 0.60 |

The adoption rule is to beat the shipped formula on **both** fixed halves of the
incidents:

| | Run 1 (A / B) | Run 2 (A / B) | Run 3 (A / B) |
|---|---|---|---|
| Shipped | 0.414 / 0.411 | 0.360 / 0.395 | 0.405 / 0.247 |
| **Age saturates at 30 d** | **0.538 / 0.557** ✅ | **0.493 / 0.532** ✅ | **0.484 / 0.385** ✅ |
| Age saturates at 45 d | 0.514 / 0.491 ✅ | 0.460 / 0.466 ✅ | 0.442 / 0.350 ✅ |
| Age weight 10% | 0.433 / 0.370 ✗ | 0.362 / 0.329 ✗ | 0.353 / 0.189 ✗ |

Each term alone, τ-b (runs 1/2/3): volume 0.40/0.32/0.27, growth
0.19/0.17/0.09, **age 0.41/0.41/0.34**, spread 0.35/0.35/0.32.

Ranker consistency, τ-b between runs: 0.881, 0.749, 0.735.

## What it shows

1. **The formula carries real signal.** It beats the naive "most reports first"
   baseline in all three runs (+0.03 to +0.05 τ-b). It recovers about half of
   what is reachable: 0.32–0.42 against a ceiling of 0.74–0.88.
2. **Age saturation at 14 days is too early.** Saturating at 30 days lifts
   agreement by +0.11 to +0.13 in every run, on both halves. Shrinking the age
   weight makes things worse. This is exactly the defect the synthetic
   calibration measured (age pinned at 1.0 for 77% of incidents) and the remedy
   it pre-registered.
3. **Growth adds little here.** In this seed few incidents have 24-hour bursts,
   so the term is mostly zero. That is a property of the data, not proof the term
   is useless.
4. **The biggest gap is category, which the formula does not read.** Every
   rationale led with hazard by category: sewage and water first, footpaths
   last. The formula deliberately has no category term. If officers confirm
   this, the planned remedy is `categories.severity_weight`, read by
   `PriorityService`. It is never a field on incidents.

## Decision

- **Applied 2026-10-22: `AGE_SATURATION_DAYS` 14 → 30** in `PriorityService`,
  and `PRODUCTION.saturation.ageDays` in `priorityMetrics.ts`. The team chose
  to accept the proxy evidence rather than wait for an officer. The evidence is
  doubly indirect (demo incidents, ranked by a model), but it is clear,
  consistent, and agrees with the change planned in advance. Weights and bands
  are unchanged.
- **Effect** on the synthetic calibration corpus
  ([priority-evaluation.md](priority-evaluation.md)): age now discriminates
  for 49% of incidents instead of 21%. Every plausibility probe still holds. A
  two-week-old incident scores up to about 1 point lower than before (it now
  earns 14/30 of the age weight), and still lands in the same band in the
  probes. Live scores change when backend-spring deploys:
  `PriorityRefreshJob` rescores every open incident within 5 minutes.
- **Still to do:** one human ranking, through the same `eval:priority`, to
  confirm or overturn it. `previous (age saturates at 14 d)` stays a variant
  so that check is one command.
- **Category severity:** take it to the officer session as a question, not a
  change.

## Limits

- **The ranker is a model, not an officer.** It may share the formula's
  blind spots, or have its own. Its run-to-run agreement measures its
  consistency, not its correctness.
- **The incidents are demo data.** Their texts come from the matching
  evaluation set, and their counts and ages come from the seed's shapes.
- **45 incidents, one snapshot.** The age term moves daily.
- **The adoption rule held on both halves in every run.** Even so, a
  pre-registered change passing a check is weaker evidence than a fresh human
  ranking.

## Reproduce

```bash
# with backend-spring running and signed in as an officer:
curl -b cookies /api/core/dashboard/ranking-sheet                  > ranking-sheet.csv
curl -b cookies '/api/core/dashboard/ranking-sheet?withScores=true' > ranking-key.csv
cd backend-node
npm run eval:priority-proxy -- ranking-sheet.csv proxy 3            # billed: 3 Gemini calls
npm run eval:priority -- ranking-key.csv proxy-1.csv proxy-2.csv proxy-3.csv
```

A human ranking goes through the same `eval:priority` command, with that
person's sheet in place of the proxy files.
