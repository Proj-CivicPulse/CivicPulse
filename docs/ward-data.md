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
SELECT ward_id, internal_code, name FROM ward_external_ids WHERE lgd_unmapped;
```

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
