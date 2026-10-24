# Matching Evaluation — Phase 7

**Status:** first evaluation, 2026-10-14. Phase 7 in [roadmap.md](roadmap.md).
**Question:** does the semantic matcher group complaints into the right
incidents, and is `MATCH_SIMILARITY_THRESHOLD = 0.75` the right cut-off?

**Answer in one paragraph.** Within a ward and category, similarity alone does
not separate "same problem" from "different problem" well, and at 0.75 it does
not separate them at all: on a labelled set with three real problems per
ward + category, every pool collapsed into **one** incident. Separation appears
only around 0.84–0.87, and even there the two distributions overlap. On real
production joins, the cheapest large win is not the threshold but **distance**:
every same-problem join was within 149 m, and a 500 m check on the matched
member removes 6 of 22 wrong joins while losing **none** of 17 right ones. That
check is now built (`MATCH_MAX_SIBLING_DISTANCE_M = 500`). **The threshold is
now 0.84** (decided 2026-10-21, §4): the recall it appeared to cost on real
joins came from seed texts that were themselves different problems. The real
labels were **reviewed on 2026-10-22** against a blind second labeller (§3a):
three moved to ambiguous, and 0.84 is still the best threshold.

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
| Size | 12 pools · 144 complaints · 216 same / 576 different pairs | 30 distinct pairs · 64 decisions (17 same, 22 different, 25 ambiguous, after the §3a review) |
| What it is | Each pool = one ward + category with **three different problems**, four complaints each, in deliberately different voices (formal, terse, angry, code-mixed Kannada/Hindi–English). Problems share locality names on purpose. | Every semantic join in `incident_match_log` below 0.90 similarity, labelled by hand with a written reason per pair. |
| Strength | Contains the hard case production data cannot: same ward, same category, similar words, different fault | Real decisions, real coordinates |
| Weakness | Authored, not collected; no coordinates | Seed texts (short, generic); small; one labeller plus a model second opinion |

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

Figures use the labels as reviewed on 2026-10-22 (§3a); the first-pass figures
(27 same, 15 ambiguous) are in git history and gave the same ordering.

| Policy | Wrong joins removed | Right joins lost | Precision of kept joins |
|---|---|---|---|
| 0.75, no distance check | 0 / 22 | 0 / 17 | 44% |
| 0.75 + distance ≤ 500 m | 6 / 22 | 0 / 17 | 52% |
| 0.80 + ≤ 500 m | 10 / 22 | 0 / 17 | 59% |
| 0.82 + ≤ 500 m | 13 / 22 | 1 / 17 | 64% |
| **0.84 + ≤ 500 m (shipped)** | **21 / 22** | **3 / 17** | **93%** |
| 0.85 + ≤ 500 m | 22 / 22 | 7 / 17 | 100% |
| 0.85, no distance check | 17 / 22 | 7 / 17 | 71% |

Distances: every **same**-problem join was within **149 m**. The **different**-
problem joins split into two groups: 16–176 m (same place, different fault —
blocked sewer vs missing drain cover, uncollected garbage vs illegal dumping)
and **843–2108 m** (same kind of fault, different place). Distance removes the
second group completely and costs nothing; only text can separate the first.

Results are insensitive to the exact cut between 250 m and 500 m (identical
here); 1000 m catches only one of the far pairs.

### 3a. Label review (2026-10-22)

The 30 pairs were labelled once, by the assistant, on 2026-10-14. To check
them, a **blind second labeller** (`gemini-3.8-flash`, `npm run
eval:second-label`) labelled every pair under the same rule, seeing the
category, both texts and the distance, never the first label or the
similarity.

| | |
|---|---|
| Raw agreement | 19 / 30 (63%) |
| Cohen's κ (three classes) | 0.45 (moderate) |
| same ↔ different flips | **0** |

Every disagreement was the second labeller answering *ambiguous*: it never
called a pair "same", and it never called a first-pass "different" pair
"same". So the two labellers disagree on how sure to be, not on direction.

Each of the 11 disagreements was re-read against the rule. **Three labels
changed to ambiguous**, where the first reason claimed more than the texts say:

- **p5:** a burst pipe flooding the road vs a pipe leaking into the drain.
  The texts don't say whether it is one main.
- **p12:** a crater "near the bus stop" vs a pothole "in the middle of the
  road", 31–202 m apart.
- **p26:** it was inconsistent with p10/p11, which pair the same lamp with the
  same outage as ambiguous.

The other eight were kept: both texts name the same fault at the same place
(p6, p13, p24: dead lamps on one dark stretch; p19: no supply and low pressure
in one area; p23, p28) or different crews and landmarks (p15, p20).

**Effect on the threshold.** It still holds, more clearly than before. Under
the reviewed labels, 0.84 + 500 m keeps one wrong join and loses 3 of 17 right
ones (it was 9 of 27). Under the second labeller's own labels, it keeps **no**
wrong join. Raw output: `backend-node/eval/results/production-joins-review-2026-10-22/`.

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

### DECIDED — the threshold is 0.84 (2026-10-21)

It was kept at 0.75 on 2026-10-14 because the two datasets disagreed about
what raising it costs:

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

**What settled it.** Recall-weighted F2 averaged over both datasets, with the
distance check on:

| Threshold | Real joins F2 | Authored F2 | Mean |
|---|---|---|---|
| 0.75 | 0.842 | 0.652 | 0.747 |
| 0.80 | 0.876 | 0.700 | 0.788 |
| **0.84** | 0.843 | **0.852** | **0.848** |
| 0.85 | 0.641 | 0.883 | 0.762 |

Real-join figures use the §3a reviewed labels; before the review the mean was
0.780 at 0.84 against 0.773 at 0.75, so the review widened the margin. 0.84 is
best on the mean and best on real-join F1 (0.875 vs 0.762 at 0.82), and cuts
the wrong real joins kept from 16 to 1. Then the cost side was checked. The
"right joins lost" were all seed reports, and the seed script built each
hotspot by rotating four **different** faults of one category: a leak, no
supply, a burst main, low pressure. By the label rule they are not one
incident, so 0.84 refusing them is correct. Re-seeding with one real problem
per hotspot (the authored set's four voices) at 0.84 put 33 of 36 hotspots in
one incident, and the 3 that split did so at a ward boundary, not on
similarity. The seed now does this.

Kept: the distance check, single-linkage, and the preference for
over-grouping as a tie-breaker. Code-mixed text remains the weakness (below).

### KEPT — single-linkage

The diameter floor did nothing on this data (§2), so there is no evidence
here to reopen the contract's single-linkage decision.

### NEXT — the two weaknesses distance cannot fix

1. **Same place, different fault** (scores 0.79–0.84). Needs a second text
   signal — e.g. a fault-type label within a category — or the higher
   threshold above.
2. **Code-mixed language.** Candidate fix: normalise text (transliterate or
   translate to English) before embedding. Evaluated 2026-10-25: §4a.

### 4a. Normalising to English before embedding (2026-10-25)

`EVAL_TEXT=normalised npm run eval:matching` first has Gemini
(`gemini-3.8-flash`, temperature 0) rewrite every one of the 144 descriptions
as plain English, keeping places, numbers and facts, then embeds the production
text. That is one call per complaint, never batched, so no complaint is
rewritten to sound like its neighbours. English complaints are rewritten too,
as production would have to do. Rewrites are cached in `eval/.cache`; results
are in `backend-node/eval/results/matching-2026-10-14-normalised.json`.

A rewrite, for example: "4th Block bus stand munde dodda gundi ide, bikes skid
aagtide, please repair maadi" became "There is a big pothole in front of 4th
Block bus stand, bikes are skidding, please repair."

| At 0.84 (production) | As shipped | Normalised |
|---|---|---|
| Cluster precision | 61.5% | **68.0%** |
| Cluster recall | 94.3% | **99.2%** |
| Cluster F1 / F2 | 0.744 / 0.852 | **0.807 / 0.909** |
| Pair F1 | 0.797 | **0.823** |
| Mixed incidents per pool | 0.63 | **0.45** |
| Lowest same-problem similarity | 0.809 | **0.850** |

| Pairing, normalised | Same problem: median (min) | Different problem: median (max) |
|---|---|---|
| English – English | 0.913 (0.850) | 0.813 (0.886) |
| English – code-mixed | **0.938** (0.860), was 0.894 (0.809) | 0.815 (0.885) |
| Code-mixed – code-mixed | — | **0.821** (0.894), was 0.841 (0.903) |

**It works on the failure §2 found.** A code-mixed report of the same problem now
sits as close to its English siblings as English reports do to each other, and
two code-mixed reports of different problems no longer score as if they were
one. The best threshold stays 0.87 on this set, so 0.84 keeps its margin.

**Decision: not switched on yet.** Two conditions, both stated before the run:
- **Real code-mixed complaints.** The code-mixed texts here were written for
  this evaluation (§5). The project's rule is to change matching only on
  evidence from real data, so normalisation ships when a real feed or real
  submissions give a labelled code-mixed sample that shows the same gain.
- **Cost and a re-embed.** It adds one model call per complaint before embedding
  (asynchronous, so a citizen still never waits), and every stored vector must
  be re-embedded from normalised text, so old and new vectors stay comparable.

When both hold, the change is small: call the same rewrite in backend-node's
embedding step behind a setting, then re-embed through the reconcile sweep.

---

## 5. Limits — what this does not show

- **The authored set was written for this evaluation**, not collected from
  residents. It is adversarial by design, which is its purpose; it is not a
  sample of real traffic and its absolute numbers are not production rates.
- **The real sample is small** (30 distinct pairs) and its texts are seed data.
  The 73 joins at ≥ 0.95 were not labelled — most are verbatim seed reuse.
- **No human labeller.** The first labeller is the assistant, the second a
  different model (κ 0.45, §3a). Labels and their reasons are in the JSON for an
  officer to overrule.
- **Time** was not evaluated, and the authored set has no coordinates, so the
  distance finding rests on the real sample alone.
