# Audit Register — disposition

> **For a release decision, read [release-readiness.md](release-readiness.md)
> instead.** It grades every issue with strict statuses, names the exact command
> proving each, and records the deployment blockers. This page is the narrative
> of what was done and why.

Status of the 16 issues raised in the requirements/code audit, checked against
the code and, where possible, against the live database.

One thing to know before reading: **the audit describes some behaviour this
repository does not have.** Most notably a "10-minute rule" that appears nowhere
in it. Those items are marked below with what was actually found.

| # | Issue | Disposition |
|---|---|---|
| 01 | Docs vs. actual matching rules | **Fixed** — the 10-minute rule did not exist; the contract is now written down |
| 02 | Copilot / hotspot stubs | **Fixed** — the API was already honest, the marketing copy was not |
| 03 | Category governance missing | **Fixed and verified** |
| 04 | Exact category matching too strict | **Fixed** via 03, verified end-to-end |
| 05 | Ward data is placeholder | **Fixed and verified** — 243 real BBMP boundaries imported |
| 06 | Nearest-centroid ward assignment | **Fixed** — PostGIS point-in-polygon is now the default |
| 07 | Priority scores go stale | **Fixed** |
| 08 | Priority not calibrated | **Evaluated** — report generated, two findings; re-tuning still needs labelled data |
| 09 | O(N²) geographic spread | **Benchmarked → do not optimise**, with numbers |
| 10 | No ingestion validation gate | **Built and verified end-to-end** |
| 11 | External/internal ward ID governance | **Fixed** — crosswalk view, 45 unmapped wards reported |
| 12 | Frontend category list hardcoded | **Fixed** |
| 13 | Node matcher lacks spatial/temporal bounds | **Resolved as a decision**, documented |
| 14 | Single-linkage chaining risk | **Demonstrated** against real pgvector; whether it harms is Phase 8 |
| 15 | Seed data insufficient | **Fixed** — alias fixtures, adversarial linkage cases, 2,000-incident corpus |
| 16 | Photo upload unresolved | **Contract decided and written**; implementation deliberately not started |

---

## The data defects found along the way

Four faults that no amount of code review would have surfaced, because they only
appear when real data meets the real database.

**The BBMP source violates GeoJSON ring semantics.** RFC 7946 says a Polygon's
rings after the first are *holes*. This dataset uses them for holes (ward 6) AND
for disjoint exclaves (wards 1, 34, 105, 206). Read literally, ward 105
(Belathur) would have lost **66% of its area**, and the residents of three
neighbourhoods would have resolved to no ward at all — invisibly. The generator
now classifies each ring geometrically.

**Nine ward boundaries were invalid geometry.** Four from the ring problem above,
five genuine self-intersections in the survey. PostGIS will not answer
`ST_Contains` reliably on invalid geometry, so those wards would have been
silently unresolvable. Repaired with `ST_MakeValid`, and the repair is
*measured*: the migration refuses to apply if any boundary moves by more than
0.5%. Actual movement: **0.0000%**.

**45 of 243 wards have no national LGD code.** The 2022 delimitation created
them and the LGD has not issued codes. Recorded as null and reported by
`ward_external_ids`, never invented.

**Two real categories were missing from the vocabulary.** V11's quarantine report
run against live data returned `noise` (4 complaints) and `footpath` (4). Neither
is a typo. V14 adds both as canonical rather than folding them into `other` —
a footpath hazard and a carriageway defect go to different works teams.

And one bug in my own work, caught by the end-to-end ingestion check: setting
`created_at` on the entity did nothing, because `@CreationTimestamp` generates
and discards it. A six-month backlog would have looked like it all arrived on
import day, handing every incident a zero age and a maximal growth score. Now
corrected by a native update after insert.

---

## Fixed

### 03 / 04 / 12 — Category governance

`category` was a free-form `VARCHAR` written by whoever was calling, while four
consumers compare it with exact equality. Four spellings of one problem meant
four incidents that could never merge.

- **V11** — `categories` + `category_aliases` (50 seeded spellings, scoped by
  `source`), backfill onto canonical codes, raw value kept in `source_category`.
- **V14** — acts on the quarantine report: `noise` and `footpath` promoted to
  canonical with 14 more aliases.
- `CategoryNormalizer` (strip accents, lowercase, collapse separators) matching
  the migration's SQL expression, so resolution stays an equality lookup and
  never becomes a fuzzy search.
- `CategoryService.require()` on the write path. `POST /complaints` now rejects
  an unresolvable category — verified live: `"interpretive dance"` →
  `400 category must be one of: pothole, streetlight, garbage, water, drainage,
  footpath, noise, other`.
- `GET /categories` (public, cached an hour); the frontend reads it, with the
  old hardcoded list demoted to a fetch-failure fallback.

Verified live: `"Solid Waste"` submitted → stored as `garbage`, with
`source_category` preserved.

Rows the backfill could not resolve were **left alone rather than swept into
`other`** — forcing them would merge unrelated complaints, worse than the
fragmentation being fixed. That decision is what surfaced `noise` and `footpath`.

### 05 / 06 / 11 — Ward data

Replaced four placeholder centroids with **243 real BBMP wards, 2022
delimitation** (KSRSAC via DataMeet, CC BY-SA 2.5 IN).

- **V13** (generated, 1.4 MB) imports boundaries as PostGIS `MultiPolygon(4326)`
  with provenance: KGIS id and code, LGD code, dataset version, effective date.
- `generate-ward-migration.mjs` refuses to emit anything unless the dataset
  passes every structural check. The committed migration is the versioned
  record — a fresh clone provisions from the repo alone, with no third-party
  fetch.
- `PostGisWardResolver` does `ST_Contains` through a GiST index and is now the
  default. **No nearest-ward fallback**: outside every boundary returns empty,
  and the caller renders a 404.
- `ward_external_ids` view is the crosswalk; an external feed may quote our ward
  number, the KGIS code or the LGD code, and the ingestion gate maps all three.
- Existing data re-homed by coordinates — incidents first, members following
  them, so no incident's members scatter across wards.

`verify-ward-import.mjs`: **all checks pass.** All 243 representative points
resolve to exactly one ward; Mysuru, Chennai, Null Island and a point just north
of the boundary resolve to none; every centroid lies inside its own ward.
167 complaints now sit across 17 real wards, none stranded.

Full detail, including the source's defects: [ward-data.md](ward-data.md).

### 07 — Priority staleness

The age term is time-dependent, so a score drifted with no write at all: an
incident that stopped attracting reports also stopped ageing.

- **V12** adds `priority_computed_at`. `updated_at` could not serve — any write
  bumps it, so a status change would mark a drifted score fresh.
- `PriorityRefreshJob` sweeps open incidents through `recomputeById`, never
  `attach`, so it cannot fabricate `incident_match_log` rows.
- Resolved and closed incidents are not swept.

### 10 — Ingestion gate

**V15** plus a four-class pipeline. Raw payload persisted before validation;
every rejection carries field, code and detail; categories and wards normalised
through the same services the public path uses; idempotent on
`(source, sourceRecordId)`; **one transaction per record** — `IngestionRecordProcessor`
is a separate bean precisely so `REQUIRES_NEW` is not defeated by
self-invocation.

`verify-ingestion.mjs` against the running app: **all checks pass.** A batch of
7 (2 good, 4 broken, 1 in-batch duplicate) → 2 accepted, 4 rejected, 1 duplicate;
the multiply-broken record reported all 6 faults; re-running the identical file
accepted 0 and grew the table by 0; an upstream edit updated in place without
creating a twin; the rejection view grouped 9 distinct faults with sample
payloads.

### 01 / 13 — Matching documentation

**There is no "10-minute rule" anywhere in this repository** — not in code,
docs, tests or config. Nothing needed removing.

What was missing was the contract written down in one place.
[matching-contract.md](matching-contract.md) now states it: the two matchers,
what the semantic one constrains (ward, category, status) and what it does not
(distance, time), why the fallback's 14-day/1.5 km bounds exist and why the two
are intentionally *not* aligned.

### 02 / 16 — Unfinished features

The API was already honest — 501s with phase references, and the submit form
already said photo upload was unavailable. The **marketing copy** was not; three
claims corrected, including "matched by meaning, not by keyword or category",
which was wrong in a way that mattered since category is a hard pre-filter.

Photo upload's open decision is now **decided**: direct-to-storage with a
server-issued upload URL, three steps, because a caller-supplied `photoUrl` —
which the DTO still accepts — lets anyone put arbitrary content in front of an
officer. Contract, validation, lifecycle and access rules in
[photo-upload-contract.md](photo-upload-contract.md). Deliberately not
implemented: it needs a storage provider chosen first.

### 15 — Fixtures

- `seed-dev-data.mjs` rotates through accepted alias spellings, so normalisation
  is exercised every run and a regression shows up as a hotspot that splits.
- `matching.linkage.test.ts` — adversarial A–B–C chains against real pgvector.
- `PriorityCalibrationTest` — a 2,000-incident corpus across 243 wards and 8
  categories, including 5% with no coordinates.

---

## Measured, then deliberately not changed

### 09 — O(N²) geographic spread

Benchmarked, as the audit instructed, before touching anything.

Real data: 31 incidents, **largest 17 members**, median 3, p95 14. An incident is
bounded by how many neighbours report one pothole, not by city size.

| members | p50 | p99 |
|---:|---:|---:|
| 17 (real max) | 0.13 ms | 0.30 ms |
| 500 (30× real max) | 10.6 ms | 13.2 ms |
| 1000 | 43 ms | 48 ms |
| 5000 | 1117 ms | 1130 ms |

The quadratic is real — 5× members costs 26× time — and it does not bite until
roughly 60× anything ever observed. **Verdict: do not optimise.** The budget (50
ms at 500 members, off the request path) is asserted by
`PriorityServiceBenchmarkTest`, so a regression fails the build.

### 08 — Priority calibration

[priority-evaluation.md](priority-evaluation.md), generated by
`PriorityCalibrationTest`. Band distribution, ablation, saturation, and seven
asserted plausibility probes.

Two findings worth carrying into Phase 8:

- **Age is saturated for 77% of the corpus**, contributing a near-constant +2.0.
  Its ablation delta (1.74) rivals volume's (1.85) at half the weight — but with
  much lower top-50 churn, which is the signature of an offset rather than a
  discriminator. Candidate responses: longer age saturation, or a smaller age
  weight.
- **Growth is zero for 62%** of incidents, discriminating for only 36%.

**No weight was changed.** The audit is right that synthetic data cannot settle
this — the generator was written by the same hand as the formula. What the report
does establish is that the model is not obviously broken: all four bands are
reachable, critical is 2.6%, ordering is sensible on every probe, and no ward or
category is structurally advantaged.

### 14 — Single-linkage chaining

**Now demonstrated rather than suspected.** Against real pgvector, with exact
stated similarities: B joins A at 0.766, then C joins the same incident at 0.766
*via B* while being **0.174** similar to A. Three ordinary complaints. The same
suite shows one near-identical member among nine distant ones sets the score —
average linkage would have rejected that incident outright.

Whether that *harms* anything is still open. A cluster can be broad and correct:
a failing water main really does produce reports that resemble each other only
pairwise along its length. That needs officer judgement, which is Phase 8.

---

## Verification

Everything below was run, not assumed.

| | |
|---|---|
| Migrations V11–V15 | applied to the live Neon database; all post-conditions passed |
| Spring context test | passes — every JPA entity validates against the migrated schema |
| backend-spring tests | 169 pass (including the context test, which applies every migration) |
| backend-node tests | 23 pass, including 5 linkage tests against real pgvector; typechecks and lints |
| frontend | typechecks, lints and builds |
| `verify-ward-import.mjs` | all checks pass |
| `verify-ingestion.mjs` | all checks pass, against the running app |
| Live smoke tests | `/categories`, `/wards/resolve` (hit and 404), alias normalisation, unknown-category rejection |

Test data created during verification was removed; the database is back to its
pre-verification 167 complaints and 31 incidents.

**One test is not run here:** `ReferenceNumberServiceTest` needs concurrent
database access and times out against a remote Neon instance from this machine.
It is unmodified by this work.
