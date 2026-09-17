# Matching Contract — what actually decides that two reports are the same problem

This document exists because an audit found the project's descriptions of
matching and the code disagreeing. Everything below is what the deployed code
does, checked against the source. Where a constraint is absent, it says so.

Read `service-boundaries.md` first for *who writes what*. This is about *what
gets decided*.

---

## 1. There are two matchers, and only one of them is the matcher

| | Semantic (real) | Naive (fallback) |
|---|---|---|
| Where | `backend-node`, `src/services/matching.service.ts` | `backend-spring`, `NaiveIncidentGrouper` |
| When it runs | every complaint, normally | only when backend-node is unreachable — connection refused, 5xx, or an open circuit breaker |
| Decides on | cosine similarity of embeddings | geographic proximity |
| Tags the complaint | `matched` | `degraded` |
| Afterwards | terminal | `MatchReconciliationJob` re-runs the real pipeline over it and corrects the grouping |

Neither of them writes. Both produce a `MatchDecision`; `IncidentAttachmentService`
in Spring performs the single transactional write either way
(`service-boundaries.md` decision 2).

**There is no 10-minute grouping rule, and there never has been.** Nothing in
the codebase implements one. If you have seen that claim, it did not come from
here.

---

## 2. What the semantic matcher constrains

Candidate incidents are narrowed **in SQL, before any vector maths**:

```sql
WHERE i.ward_id  = $2
  AND i.category = $3
  AND i.status IN ('OPEN', 'IN_PROGRESS')
  AND c.embedding IS NOT NULL
  AND c.id <> $4
```

Then, among those candidates, `top_similarity >= MATCH_SIMILARITY_THRESHOLD`
(0.75) decides. Score is **single-linkage**: an incident scores the MAX
similarity to any one of its member complaints.

So, precisely:

- **Ward — hard constraint.** Cross-ward matching is impossible, and
  `IncidentAttachmentService` throws if a decision proposes one anyway. Since
  V13 the ward itself is derived by real point-in-polygon against imported BBMP
  boundaries, so this constraint is now trustworthy as well as strict — see
  [ward-data.md](ward-data.md).
- **Category — hard constraint, exact equality.** Meaning decides *within* a
  category, never across one. Since the category registry (migration V11) that
  equality is over canonical codes, so `Garbage`, `solid waste` and
  `Uncollected Garbage` are one candidate pool rather than three — but
  `pothole` and `drainage` still cannot merge, by design.
- **Status — hard constraint.** Resolved and closed incidents do not attract
  new reports.
- **Distance — NOT constrained.** Two complaints at opposite ends of a ward can
  match on meaning alone.
- **Time — NOT constrained.** An incident that has been open for months is as
  eligible a candidate as one opened this morning.

The last two are the ones most often assumed to exist. They do not.

### Why the two unconstrained axes are left unconstrained

Ward and status already bound the search hard. Within one ward, "far apart" is
a few kilometres, and two reports of *the same ongoing problem* legitimately
can be — a failing water main is not a point. A distance cut-off tuned wrong
splits one real incident into several, which is the failure the whole feature
exists to prevent.

Time is bounded structurally instead: an incident stops attracting reports when
an officer resolves it. A stale-incident policy belongs to that workflow, not
to the matcher.

**This is a decision, not an oversight — but it is an unvalidated one.** See
open question 1. It is ACCEPTED for this release: ward and status bound the
search, and no evidence yet shows a distance- or time-distant match that an
officer would call wrong.

---

## 3. What the naive fallback constrains

Different constraints, deliberately, because it has no similarity signal to
work with and needs every other bound it can get:

- same ward
- same category (exact equality)
- status in OPEN / IN_PROGRESS
- **opened within `app.incident-grouping.window-days` (14 days)**
- **centroid within `app.incident-grouping.max-distance-km` (1.5 km)**
- nearest candidate wins

It will produce bad merges — two unrelated potholes 400 m apart become one
incident. That is accepted: the alternative during a Node outage is complaints
landing nowhere and officers being blind to them until reconciliation runs.
Every decision is logged, and every correction the reconcile sweep later makes
is recorded as a `RECONCILED` row in `incident_match_log` — which is a labelled
disagreement between the naive baseline and the real matcher, and therefore
free evaluation data for Phase 8.

**The two matchers are intentionally not aligned.** Making the semantic matcher
adopt the fallback's window and radius would be adopting the crude bounds of the
thing it exists to replace.

---

## 4. Single-linkage and the chaining risk

An incident's score is the maximum similarity to any *one* member. So A can
join because it resembles B, and C can later join because it resembles A, even
though C barely resembles B. Over a long-lived incident the cluster can broaden.

It was chosen for explainability: "matched complaint #4821 at 0.89" is
something an officer can click into and verify. Average- or centroid-linkage
gives a number that corresponds to nothing they can look at, and centroid
similarity decays as a legitimate long-running incident accumulates variety.

Mitigations in place: the threshold, ward + category + status bounds, and every
candidate score logged above *and* below threshold so the chain is
reconstructable after the fact.

**This is no longer hypothetical.** `matching.linkage.test.ts` reproduces it
against real pgvector: with A, B and C each 40 degrees apart, B joins A at 0.766
and C then joins the same incident at 0.766 via B — while being 0.174 similar to
A. Three complaints is all it takes.

### DECISION: keep single-linkage for this release

Decided 2026-09-17, in the pre-deployment audit. This is a product decision, not
a deferral — chaining is a **known and accepted** behaviour of the shipped
matcher.

| | |
|---|---|
| **Decision** | Ship single-linkage (`MAX` similarity) unchanged. |
| **Why** | It is the only linkage rule that yields an explanation an officer can check. "Matched complaint #4821 at 0.89" is a row they can open; an average or centroid score corresponds to nothing they can look at. Explainability is a headline claim of this project, not a nice-to-have. |
| **Accepted risk** | A cluster's *diameter* can exceed the threshold that admitted each member. Reachable in three complaints. Worst realistic case: one incident that should have been two, which an officer sees as one over-broad item rather than as a missing one. |
| **Why that risk is tolerable now** | The failure is visible and recoverable — an officer looking at the incident sees unrelated reports in it. The opposite failure (two incidents that should have been one) is invisible, and is the failure the whole feature exists to prevent. Given a forced choice, over-grouping is the safer error. |
| **Guardrails already in place** | ward + category + status hard pre-filters; threshold 0.75; every candidate score logged above *and* below threshold, so any chain is reconstructable after the fact. |
| **Revisit trigger** | Phase 8 labelling shows chained clusters an officer calls wrong, OR an incident exceeds ~25 members with low pairwise coherence, OR an officer reports an incident containing unrelated problems. |
| **First mitigation to try** | A bounded cluster *diameter*: admit a candidate only if its similarity to the incident's WORST-matching member clears a lower floor (say 0.55). This caps chaining while keeping the clickable top-sibling explanation intact — unlike complete-linkage, which discards it. |
| **Do NOT** | switch to average or centroid linkage without labelled evidence. Both cost the explanation and centroid similarity decays as a legitimate long-running incident broadens. |

---

## 5. What is measured, and where

`incident_match_log` records every decision — semantic and naive, joins and
creates and reconcile moves — with `top_similarity`, `threshold`,
`candidate_count`, `top_sibling_complaint_id`, `model` and `embedding_dim`.
`matching.service.ts` logs every candidate's score, not just the winner.

That is the dataset Phase 8's precision/recall/F1 evaluation runs on. Nothing
below should be changed before it does.

---

## 6. Open questions — deliberately not answered yet

These need labelled data, not a decision.

1. **Should the semantic matcher bound distance or time?**
   Decide from the log: among matched pairs, what is the distribution of
   distance and of age gap? If the tail contains matches an officer would call
   wrong, add the bound that excludes them — and add it as *post-hoc validation
   of a semantic candidate*, not as another pre-filter, so the log still records
   what similarity alone would have done.

2. **Is single-linkage chaining actually harming clusters?** *(the behaviour is
   DECIDED — see section 4; this is the measurement that would reopen it)*
   **Chaining is now demonstrated, not merely suspected.**
   `backend-node/src/services/matching.linkage.test.ts` builds A–B–C against
   real pgvector with exact, stated similarities: B joins A at 0.766, then C
   joins the same incident at 0.766 via B while being only **0.174** similar to
   A. Three ordinary complaints are enough. The same suite shows the score is
   set by one near-identical member among nine distant ones — average linkage
   would have rejected that incident outright.

   What is still open is whether that is HARMING anything. A cluster can be
   broad and still correct: a failing water main really does produce reports
   that resemble each other only pairwise along its length. Measuring cluster
   coherence against officer judgement is Phase 8 work. Complete-linkage or a
   bounded cluster diameter are the alternatives, and both cost the
   explainability that single-linkage buys.

3. **Is 0.75 the right threshold?**
   The measured similarity distribution leaves a thin margin. Phase 8 must label
   a sample before this moves.

4. **Should the ingestion path share the matcher's assumptions?**
   Externally ingested complaints go through the same `ComplaintCreatedEvent`
   and therefore the same matcher — deliberately, so there is one matching
   contract rather than two. But an imported backlog arrives with old
   `created_at` values all at once, which the growth term has never seen. Watch
   the first real import.

5. **Should the category pre-filter be relaxed?**
   The registry fixed the *spelling* problem — equivalent labels are now one
   code. What it cannot fix is a genuinely mis-categorised report (a resident
   files a blocked drain as `other`). Relaxing the filter to sibling categories
   is possible via `categories.parent_id`, which exists and is unused. Do not do
   it before measuring how often mis-categorisation actually occurs; the cost is
   a much larger candidate set and cross-department merges.
