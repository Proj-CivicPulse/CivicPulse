-- Persist the ward overlap check alongside the five V17 recorded.
--
-- The 2026-10-13 audit (docs/ward-data.md, "Overlap check") found the BBMP
-- boundaries are not a perfect tiling: 26 pairs of wards overlap, 50.94 m² in
-- total, the largest 8.70 m². They are digitisation slivers along shared edges,
-- no complaint lies in one, and the resolver handles a point inside one
-- deterministically (lowest ward id). Recorded rather than repaired -- editing
-- survey boundaries by hand has nothing to check the edit against.
--
-- Persisted so the NEXT delimitation import has a baseline. "Is the new
-- dataset's overlap worse than the old one's?" can only be asked of a number
-- that was written down.
--
-- RE-DERIVED, not transcribed: the SELECT below measures the current rows, so
-- this row is a measurement taken when the migration ran.
--
-- Threshold: no single ward pair may overlap by more than 100 m², about one
-- residential plot. Below that it is a sliver; above it two wards are claiming
-- real property, which is a boundary dispute rather than a digitising artefact.
-- The check is on the LARGEST pair, not the total, because one 500 m² overlap
-- is a real problem while fifty 1 m² slivers are not.

WITH pair_overlaps AS (
    SELECT ST_Area(ST_Intersection(a.boundary, b.boundary)::geography) AS m2
    FROM wards a
    JOIN wards b ON a.id < b.id AND ST_Intersects(a.boundary, b.boundary)
    WHERE a.source = 'kgis-bbmp-2022'
      AND b.source = 'kgis-bbmp-2022'
      AND NOT ST_Touches(a.boundary, b.boundary)
),
summary AS (
    SELECT COALESCE(MAX(m2), 0) AS largest,
           COALESCE(SUM(m2), 0) AS total,
           COUNT(*) FILTER (WHERE m2 > 0) AS pairs
    FROM pair_overlaps
)
INSERT INTO dataset_validations
    (dataset_name, dataset_version, check_name, subject, status,
     observed, threshold, unit, detail, checked_at)
SELECT 'bbmp-wards', '2022-delimitation', 'boundary-overlap', NULL,
       CASE WHEN s.largest <= 100 THEN 'PASS' ELSE 'WARN' END,
       round(s.largest::numeric, 2)::double precision,
       100::double precision,
       'm2 largest pair overlap',
       format('%s ward pairs overlap, %s m2 in total. Digitisation slivers along '
              'shared edges, recorded not repaired; a point inside one resolves to '
              'the lower ward id. WARN (not FAIL) above threshold: an overlap is a '
              'source defect to take upstream, not a reason to refuse the import. '
              'RE-DERIVED by V20 from the current boundaries.',
              s.pairs, round(s.total::numeric, 2)),
       NOW()
FROM summary s;
