# Ward Data — what is in the database and where it came from

An audit flagged ward data as the highest-severity gap in the project: four
placeholder centroids standing in for a city. That is closed. This document
records what replaced them, what was wrong with the source, and what is still
approximate.

---

## The dataset

**243 BBMP wards, 2022 delimitation.**

| | |
|---|---|
| Source | KSRSAC (Karnataka State Remote Sensing Applications Centre), [kgis.ksrsac.in](https://kgis.ksrsac.in/bengalurugis/) |
| Published by | [DataMeet / Municipal_Spatial_Data](https://github.com/datameet/Municipal_Spatial_Data/tree/master/Bangalore), `BBMP.geojson` |
| Licence | Creative Commons Attribution-ShareAlike 2.5 India |
| Imported by | `V13__bbmp_ward_boundaries.sql` (generated, 1.4 MB) |
| Generator | `backend-spring/scripts/generate-ward-migration.mjs` |
| Verifier | `backend-spring/scripts/verify-ward-import.mjs` |

Each ward row carries: internal id, ward number (`code`, 1–243), official name,
KGIS ward id and code, national LGD code where one exists, the boundary as a
PostGIS `MultiPolygon(4326)`, a derived centroid, the dataset version, and the
effective date.

The migration is **generated and committed**, not fetched at runtime. A fresh
clone provisions the same 243 wards from the repository alone, with no call to
a third-party host that may move or change. Re-run the generator against a newer
delimitation and diff the output.

---

## How a complaint gets a ward

`PostGisWardResolver` — real point-in-polygon, `ST_Contains` against the
imported boundary, through a GiST index. It is the default
(`app.ward-resolver=postgis`).

Two properties are worth stating because they are load-bearing:

- **A point outside every ward resolves to NOTHING.** There is no
  nearest-ward fallback, deliberately. The caller renders the empty result as a
  404 and the submit form falls back to the manual picker. Substituting the
  least-distant ward would reintroduce exactly the silent mis-filing that
  point-in-polygon was imported to remove — and would do it precisely where the
  answer is least trustworthy.
- **`CentroidWardResolver` still exists**, selectable with
  `app.ward-resolver=centroid`, for a database without PostGIS or ward data that
  arrived without geometry. It is no longer the default and is no longer what
  anything runs on.

### Why this mattered

Ward is a **hard constraint on matching**: both matchers filter candidates by
`ward_id`, and the attach path refuses a cross-ward decision. A complaint in the
wrong ward therefore cannot group with the reports it belongs with — with no
error, anywhere. Nearest-centroid disagreed with containment near every boundary
of every irregular ward, which is most of them.

---

## What the source got wrong, and what was done about it

The import found three real defects. All three are the kind that would have
validated cleanly and been wrong.

### 1. Extra rings are exclaves, not holes (4 wards)

RFC 7946 says a Polygon's first ring is the exterior and every later ring is a
**hole**. This dataset uses later rings for both. Ward 6 (Kogilu) genuinely has
an enclave; wards 1, 34, 105 and 206 use extra rings for **disjoint parts**
lying wholly outside the first ring.

Read literally, those became holes. PostGIS rejected four of them outright
("Hole lies outside shell") — and had it not, **ward 105 (Belathur) would have
lost 66% of its area**, leaving the residents of three separate neighbourhoods
resolving to no ward at all.

The generator now classifies each ring geometrically: inside an existing part →
that part's hole; outside every part → a new part. The result is a MultiPolygon
that says what the survey meant.

### 2. Self-intersecting rings (5 wards)

Wards 48, 49, 136, 162 and 164 carry rings that cross themselves. PostGIS will
not answer `ST_Contains` reliably on invalid geometry, so these wards would have
been silently unresolvable.

Repaired with `ST_MakeValid` — but **the repair is measured**. `ST_MakeValid` is
not unconditionally safe; on badly broken input it can return something of a
wildly different area, which is a boundary that quietly moved. The migration
computes the area change and refuses to apply if it exceeds 0.5%.

Measured result: **0.0000%**. These were genuine topology errors, not
disagreements about where the ward is.

### 3. 45 wards have no national LGD code

Wards 199–243 — the ones created by the 2022 delimitation — have no Local
Government Directory code in the source. The column is therefore **nullable and
not unique**, and the absence is recorded rather than invented.

`ward_external_ids` reports them:

```sql
SELECT ward_id, internal_code, name FROM ward_external_ids
WHERE lgd_unmapped AND source = 'kgis-bbmp-2022';
```

The `source` filter matters: the view covers every ward with a source, which
includes the four retired `placeholder-v2` rows. Without it the query returns
**49** rows, not 45 — the placeholders have no LGD code either, but they are not
part of the delimitation and are not what the WARN is about.

This is the ward crosswalk ISSUE-11 asked for, as a view rather than a table so
it cannot go stale. An external feed may quote our ward number, the KGIS code,
or the LGD code, and the ingestion gate maps all three — `IngestionValidator`
refuses an unmappable value rather than guessing.

---

## Existing data

V13 re-homed everything that was sitting in a placeholder ward, by actual
coordinates. **Incidents move first and their members follow them**: remapping
each complaint independently would scatter one incident's members across several
real wards wherever a group straddles a boundary, breaking the invariant
`IncidentAttachmentService` enforces and making those incidents unattachable
thereafter.

Result: 167 complaints and 31 incidents, spread across 17 real wards, none
stranded.

The four placeholder wards are **retired, not deleted** (`active = false`,
code prefixed `LEGACY-`). Complaints, incidents and the reference-number
counters all hold foreign keys to them, and deleting rows out from under an
issued reference number would be worse than keeping four inert rows. They are
excluded from every picker, every ward strip, and both resolvers.

**Reference numbers issued before the import encode a placeholder ward**
(`CP-2026-W17-…`). Those are historical and were never printed for a real
resident; they are left alone, because a reference number a person holds must
never change underneath them.

---

## Verification

```bash
cd backend-spring && node scripts/verify-ward-import.mjs
```

All checks pass. Notably:

- all 243 representative points resolve to **exactly one** ward
- Mysuru, Chennai, Null Island and a point just north of the boundary resolve to
  **none**
- every derived centroid lies **inside its own ward** — `ST_PointOnSurface`, not
  `ST_Centroid`, because a true centroid of a concave ward can land in a
  neighbouring one
- the four multi-part wards kept their parts; Kogilu kept its enclave
- no complaint sits outside the ward it is filed in

---

## Audit — 2026-10-13

Measured against the live database (Flyway head V18). Read-only; the queries
are below so the next audit can re-run them.

### Counts

| | Wards | `source` | `active` |
|---|---|---|---|
| Active, 2022 delimitation | **243** | `kgis-bbmp-2022` | true |
| Retired placeholders (`LEGACY-*`) | **4** | `placeholder-v2` | false |
| Total rows | **247** | | |

There is no third state. "Retired" and "placeholder" are the same four rows;
every active ward is a real one.

Activity is concentrated: complaints exist in **17** of the 243 wards. Every
complaint point lies inside exactly one active ward, and none lies outside all
of them.

### LGD mapping coverage

| | Wards |
|---|---|
| With a national LGD code | **198** (wards 1–198, all distinct) |
| Without | **45** (exactly wards 199–243) |

This is the persisted `dataset_validations` row `bbmp-wards / 2022-delimitation
/ lgd-code-coverage`, status **WARN**, observed 45, threshold 0. The other four
rows for this dataset — `ward-count`, `geometry-validity`,
`centroid-containment`, `repair-area-drift` — are **PASS**. The unmapped set is
exactly the wards the 2022 delimitation created, and nothing else.

### Overlap check

Not previously checked either way. **The boundaries are not a perfect tiling.**

| | |
|---|---|
| Ward pairs whose interiors overlap | **26** |
| Total overlapping area | **50.94 m²** |
| Largest single overlap | 8.70 m² (wards 87 / 88) |
| Share of the city's 716.28 km² | ~0.00000007 |
| Complaints inside an overlap | **0** |

These are digitisation slivers along shared edges — the largest is smaller than
a parking space — not two wards claiming the same neighbourhood. They are
recorded rather than repaired: editing survey boundaries to remove them would be
a hand edit to source data with no reference to check it against.

**What happens to a point inside one.** `WardRepository.findContaining` orders
by `w.id` and takes the first, so a point in a sliver resolves deterministically
to the ward with the lower internal id — the same answer every time, which is
the property that matters for matching. It is not "the right" ward, because at
this scale there isn't one.

**And a point exactly on a shared edge** resolves to **no ward** (404), because
`ST_Contains` excludes a polygon's boundary. At six-decimal coordinate precision
that requires a GPS fix landing on the line to within ~0.1 m, so it is a
theoretical case, not a practical one.

`V20__ward_overlap_validation.sql` persists this as a sixth `bbmp-wards` row,
`boundary-overlap`: re-derived from the boundaries when it runs, **PASS** while
no single pair overlaps by more than 100 m² (about one residential plot), WARN
above. The largest pair is the measure, not the total — one 500 m² overlap is a
boundary dispute, fifty 1 m² slivers are not. It is the baseline the next
delimitation import is compared against.

### Re-running this audit

```sql
-- Counts by source and state
SELECT source, active, count(*) FROM wards GROUP BY 1, 2 ORDER BY 1, 2;

-- LGD coverage, scoped to the delimitation
SELECT count(*) AS total, count(lgd_ward_code) AS with_lgd,
       count(DISTINCT lgd_ward_code) AS distinct_lgd
FROM wards WHERE source = 'kgis-bbmp-2022';

-- Persisted validation results
SELECT check_name, status, observed, threshold, unit
FROM dataset_validations WHERE dataset_name = 'bbmp-wards' ORDER BY id;

-- Overlaps between active wards (interiors, not shared edges)
SELECT a.code, b.code,
       round(ST_Area(ST_Intersection(a.boundary, b.boundary)::geography)::numeric, 2) AS m2
FROM wards a JOIN wards b ON a.id < b.id AND ST_Intersects(a.boundary, b.boundary)
WHERE a.active AND b.active AND NOT ST_Touches(a.boundary, b.boundary)
ORDER BY 3 DESC;

-- Complaints contained by more than one active ward — MUST be empty
SELECT c.id FROM complaints c
JOIN wards w ON w.active
 AND ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(c.longitude, c.latitude), 4326))
GROUP BY c.id HAVING count(*) > 1;
```

---

## What is still approximate

- **Zones are null.** The KGIS dataset carries no zone, and BBMP's 8 zones are
  not derivable from it. `WardDto.zone` is null for every imported ward.
- **The 2022 delimitation is contested.** Bengaluru's municipal geography has
  been redrawn repeatedly and is subject to the Greater Bengaluru Authority
  reorganisation. `dataset_version` and `effective_from` exist so a future
  delimitation is a new import alongside this one, not an edit to it.
- **Boundary precision is 6 decimal places** (~0.11 m), far finer than the
  survey or any phone GPS fix, so the rounding cannot change a real point's
  ward.
