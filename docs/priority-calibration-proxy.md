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
4. **The biggest gap is hazard, which the formula does not read.** Every
   rationale led with hazard by category: sewage and water first, footpaths
   last. The formula deliberately has no category term. The question was
   settled on real-world evidence below; the answer is no category weight.

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
- **Final (2026-10-26):** the team adopted this calibration as the model's
  last word; no human ranking is planned. `previous (age saturates at 14 d)`
  stays a variant in `eval:priority` for the record.
- **Closed 2026-10-22: no category severity weight.** Decided on published
  evidence (next section), not on a ranking.

## Category severity — decided against, on real-world evidence

The question: should the score carry a per-category hazard weight
(`categories.severity_weight`)? The evidence from India and Bengaluru:

| Category | What the record shows |
|---|---|
| Potholes | ~1,856 deaths in India in 2022 attributed to potholes, up from 1,481 in 2021 (MoRTH, via [DT Next](https://www.dtnext.in/edit/highway-hazard-746663)); 1,68,491 road deaths in total ([Outlook Business](https://www.outlookbusiness.com/amp/story/news/168-lakh-people-died-in-461-lakh-road-accidents-in-2022-road-ministry-report)) |
| Electrical / streetlights | ~13,446 electrocution deaths in 2020 (NCRB, via [DNA India](https://www.dnaindia.com/india/report-electrocution-deaths-30-people-killed-every-year-in-india-ncrb-data-2973160)), mostly domestic and occupational; 20 in five months in Bengaluru per BESCOM ([Deccan Herald](https://www.deccanherald.com/amp/story/india%2Fkarnataka%2Fbengaluru%2F20-electrocution-deaths-in-five-months-bescom-data-1129242)) |
| Storm-water drains | repeated deaths of children and adults in uncovered drains in Bengaluru, a PIL, and a High Court fencing undertaking ([The Quint](https://www.thequint.com/news/india/stormwater-drains-in-bengaluru-continue-to-claim-lives), [Citizen Matters](https://citizenmatters.in/death-of-9-year-old-in-storm-water-drain-pil-filed-against-bbmp), [The News Minute](https://www.thenewsminute.com/article/all-open-drains-bengaluru-be-fenced-sept-2020-civic-body-hc-115956)) |
| Water supply | contamination outbreaks: 6 confirmed cholera cases in Karnataka in early 2024 officially, more reported by private hospitals ([Udayavani/PTI](https://english.udayavani.com/article/six-cholera-cases-in-2024-no-outbreak-karnataka-health-dept/1626398)) |
| Street lighting (dark streets) | police link dark spots to crime ([Deccan Chronicle](https://www.deccanchronicle.com/nation/crime/161118/fix-dark-spots-police-to-bbmp.html)); ~37% of 3,075 km surveyed was not safely lit ([Deccan Herald](https://www.deccanherald.com/india/karnataka/bengaluru/nocturnal-safety-switched-off-2041254)); no Bengaluru study quantifies the harm |

**Decision: no category weight.** Three reasons:

1. **The hazard belongs to the problem, not the category.** The deaths come
   from an *uncovered* drain, a *deep* pothole on a main road, an *exposed*
   live wire. The same categories also hold "lights on all day", "low pressure"
   and "a rocking slab", which are real problems but not dangers. The evaluation
   set's own problems show this split inside every category. A category weight
   would raise the harmless reports exactly as much as the dangerous ones, and
   still could not tell them apart.
2. **There is no per-incident risk to set it from.** National death counts
   measure exposure times frequency across a country, not the danger of one
   reported incident. Turning them into weights would be judgement presented
   as data, the thing priority-evaluation.md warns against.
3. **The proxy cannot show otherwise.** It saw only the category, never the
   report's text, so ranking by category was all it could do. Its preference
   says hazard matters. It does not say category measures hazard.

**What the evidence does support** is flagging **life-safety problems**:
open or uncovered drains, exposed wiring, sewage overflow, collapsed road. The
flag would be set on the incident, either by the officer or from report
text, and would feed decision support (escalate) rather than the score. It is
in the roadmap backlog. It needs a sub-type taxonomy or text classification,
plus an officer's confirmation, before it can be trusted.

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
