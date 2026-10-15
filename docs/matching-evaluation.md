# Matching Evaluation — Phase 7

**Status:** first evaluation, 2026-10-14. Post-midsem roadmap §17.
**Question:** does the semantic matcher group complaints into the right
incidents, and is `MATCH_SIMILARITY_THRESHOLD = 0.75` the right cut-off?

**Answer in one paragraph.** Within a ward and category, similarity alone does
not separate "same problem" from "different problem" well, and at 0.75 it does
not separate them at all: on a labelled set with three real problems per
ward + category, every pool collapsed into **one** incident. Separation appears
only around 0.84–0.87, and even there the two distributions overlap. On real
production joins, the cheapest large win is not the threshold but **distance**:
every same-problem join was within 202 m, and a 500 m check on the matched
member removes 6 of 22 wrong joins while losing **none** of 27 right ones. That
check is now built (`MATCH_MAX_SIBLING_DISTANCE_M = 500`). The threshold stays
at 0.75 pending a team decision, because raising it trades real recall for
precision in a way the evidence does not settle — the numbers for that choice
are in §4.

---

## 1. What was measured

The production rule (`backend-node/src/services/matching.service.ts`): a new
complaint is compared only with active incidents in **the same ward and
category**, scored by cosine similarity to each incident's **most similar
member** (single-linkage), and joins the best incident if that score clears the
threshold. Embeddings: `gemini-embedding-001`, 1536 dimensions,
`SEMANTIC_SIMILARITY`, text `"[category] description"`. The evaluation uses
exactly this call and text builder, so it measures the shipped rule.

Two datasets, because neither is enough alone.

| | Authored set | Real production joins |
|---|---|---|
| File | `backend-node/eval/matching-eval-set.json` | `backend-node/eval/production-joins-labelled.json` |
| Size | 12 pools · 144 complaints · 216 same / 576 different pairs | 30 distinct pairs · 64 decisions (27 same, 22 different, 15 ambiguous) |
| What it is | Each pool = one ward + category with **three different problems**, four complaints each, in deliberately different voices (formal, terse, angry, code-mixed Kannada/Hindi–English). Problems share locality names on purpose. | Every semantic join in `incident_match_log` below 0.90 similarity, labelled by hand with a written reason per pair. |
| Strength | Contains the hard case production data cannot: same ward, same category, similar words, different fault | Real decisions, real coordinates |
| Weakness | Authored, not collected; no coordinates | Seed texts (short, generic); small; single labeller |

**Label rule (both sets):** two complaints are the same incident iff they
describe the same physical fault that one repair job would fix. Where two
texts could plausibly share one root cause (a burst main causing "no supply"
nearby), the pair is **ambiguous** and excluded from headline figures.

Reproduce:

```bash
cd backend-node
npm run eval:matching            # authored set; embeds once, then cached
EVAL_TEXT=plain npm run eval:matching   # ablation: no "[category]" prefix
npm run eval:production-joins    # real labelled joins; no API calls
```

Results are saved to `backend-node/eval/results/`.

---

## 2. Authored set: similarity alone

### Distributions

| | min | p5 | p10 | median | p90 | p95 | max |
|---|---|---|---|---|---|---|---|
| Same problem (216 pairs) | 0.809 | 0.849 | 0.859 | 0.904 | | | |
| Different problem (576 pairs) | | | | 0.814 | 0.849 | 0.860 | 0.903 |

Every different-problem pair scores above 0.75. The two ranges overlap from
0.809 to 0.903.

### Threshold sweep

"Pair" judges each pair on its own. "Cluster" replays arrivals through the
production single-linkage rule (25 seeded orders per pool), so a pair chained
together through a third complaint counts as grouped — that is how chaining
shows up. Truth is 3 incidents per pool. F2 weights recall twice as heavily as
precision, because [matching-contract.md](matching-contract.md) §4 holds that
over-grouping is the safer error.

| Threshold | Pair P | Pair R | Pair F1 | Cluster P | Cluster R | Cluster F1 | Cluster F2 | Incidents / pool | Mixed incidents / pool |
|---|---|---|---|---|---|---|---|---|---|
| **0.75 (prod)** | 27.4% | 100% | 0.430 | 27.3% | 100% | 0.429 | 0.652 | **1.00** | 1.00 |
| 0.80 | 35.0% | 100% | 0.518 | 32.3% | 98.9% | 0.487 | 0.700 | 1.37 | 1.00 |
| 0.82 | 47.1% | 98.6% | 0.638 | 40.4% | 97.0% | 0.570 | 0.758 | 1.83 | 0.94 |
| 0.84 | 68.0% | 96.3% | 0.797 | 61.5% | 94.3% | 0.744 | 0.852 | 2.61 | 0.63 |
| 0.85 | 78.8% | 94.4% | 0.859 | 71.9% | 93.6% | 0.813 | **0.883** | 2.86 | 0.47 |
| 0.86 | 86.8% | 88.0% | **0.874** | 79.6% | 90.2% | 0.846 | 0.879 | 3.17 | 0.38 |
| 0.87 | 94.1% | 81.0% | 0.871 | 89.2% | 85.2% | **0.871** | 0.860 | 3.58 | 0.28 |
| 0.90 | 98.3% | 53.7% | 0.695 | 97.0% | 60.3% | 0.743 | 0.652 | 5.45 | 0.10 |

At 0.75 every pool became one incident holding all three problems.

### Why: language, not the category prefix

**The `[category]` prefix is not the cause.** Embedding the bare description
lowers every score by about 0.02 but leaves separation unchanged (best pair F1
0.870 at 0.84 without it, 0.874 at 0.86 with it). The prefix stays.

**Code-mixed text is.** Split by the language of each side:

| Pairing | Same problem: median (min) | Different problem: median (max) |
|---|---|---|
| English – English | 0.912 (0.850) | 0.813 (0.883) |
| English – code-mixed | 0.894 (0.809) | 0.809 (0.886) |
| Code-mixed – code-mixed | — | **0.841 (0.903)** |

Two code-mixed Kannada–English complaints about different problems score as
high as most same-problem English pairs: the model partly groups by language.
The hardest negatives in the whole set are all such pairs ("Bull Temple Road
pipe burst" vs "DVG Road dirty water", 0.903). Bengaluru residents will write
like this, so this is a real limit of similarity-only matching.

### Chaining

The contract's first mitigation for chaining — a **diameter floor** (a
candidate must also clear 0.45–0.60 against the incident's *least* similar
member) — had **no effect** at 0.75: every pair in a pool already exceeds 0.76,
so no floor in that range can bind. Chaining is not the failure here; the
threshold is.

---

## 3. Real production joins: similarity and distance

64 real join decisions, all made at 0.75. For each candidate policy: how many
wrong joins it would have prevented and how many right ones it would have lost.

| Policy | Wrong joins removed | Right joins lost | Precision of kept joins |
|---|---|---|---|
| 0.75, no distance check (today) | 0 / 22 | 0 / 27 | 55% |
| **0.75 + distance ≤ 500 m** | **6 / 22** | **0 / 27** | 63% |
| 0.80 + ≤ 500 m | 10 / 22 | 4 / 27 | 66% |
| 0.82 + ≤ 500 m | 13 / 22 | 7 / 27 | 69% |
| 0.84 + ≤ 500 m | 21 / 22 | 9 / 27 | 95% |
| 0.85 + ≤ 500 m | 22 / 22 | 13 / 27 | 100% |
| 0.85, no distance check | 17 / 22 | 13 / 27 | 74% |

Distances: every **same**-problem join was within **202 m**. The **different**-
problem joins split into two groups: 16–176 m (same place, different fault —
blocked sewer vs missing drain cover, uncollected garbage vs illegal dumping)
and **843–2108 m** (same kind of fault, different place). Distance removes the
second group completely and costs nothing; only text can separate the first.

Results are insensitive to the exact cut between 250 m and 500 m (identical
here); 1000 m catches only one of the far pairs.

---

## 4. Decisions

### DECIDED — add a distance check (built)

`MATCH_MAX_SIBLING_DISTANCE_M = 500` (0 disables). A semantic candidate is
accepted only if its most similar member lies within 500 m of the new
complaint; a too-far candidate is skipped and the next one considered.

It is a **post-hoc check, not a pre-filter**, exactly as
[matching-contract.md](matching-contract.md) open question 1 required: the
candidate query still ranks and logs by similarity alone, so
`incident_match_log` keeps recording what similarity would have decided. A
`CREATED` decision whose `top_similarity` clears the threshold is explained by
the distance check (`refusedByDistance` in the Node log).

**Takes effect on the next backend-node deploy** — Railway does not pin this
variable, so the code default applies.

### OPEN — the threshold (team decision)

Kept at **0.75** for now. The two datasets disagree about what raising it
costs:

- On the **authored** set, 0.75 is no filter at all and 0.85 is best by
  recall-weighted F2 (0.883 vs 0.652).
- On **real** joins (with the distance check), raising it to 0.84 removes
  21 of 22 wrong joins but loses **a third** of right ones — the short,
  generic seed reports score 0.80–0.84 even when they are the same problem.
  By recall-weighted F2, 0.75 + distance scores best on real data.

The contract's stated preference (over-grouping is the safer, visible error)
favours staying at 0.75 + distance. If the team instead decides precision
matters more — fewer mixed incidents for officers to split by hand — the
evidence points to **0.84 + 500 m** (real precision 95%; authored F2 0.852).
Either way it is a Railway variable change (`MATCH_SIMILARITY_THRESHOLD`), no
code. Real resident complaints, once they exist, are what will settle it:
re-run `npm run eval:production-joins` after labelling a fresh sample.

### KEPT — single-linkage

The diameter floor did nothing on this data (§2), so there is no evidence
here to reopen the contract's single-linkage decision.

### NEXT — the two weaknesses distance cannot fix

1. **Same place, different fault** (scores 0.79–0.84). Needs a second text
   signal — e.g. a fault-type label within a category — or the higher
   threshold above.
2. **Code-mixed language.** Candidate fix: normalise text (transliterate or
   translate to English) before embedding. Must be evaluated with this
   harness before shipping; it changes every stored vector.

---

## 5. Limits — what this does not show

- **The authored set was written for this evaluation**, not collected from
  residents. It is adversarial by design, which is its purpose; it is not a
  sample of real traffic and its absolute numbers are not production rates.
- **The real sample is small** (30 distinct pairs) and its texts are seed data.
  The 73 joins at ≥ 0.95 were not labelled — most are verbatim seed reuse.
- **One labeller** (the assistant), no inter-rater agreement. Labels and their
  reasons are in the JSON for an officer to review and overrule.
- **Time** was not evaluated, and the authored set has no coordinates, so the
  distance finding rests on the real sample alone.
