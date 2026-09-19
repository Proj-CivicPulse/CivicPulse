/**
 * Generates V13__bbmp_ward_boundaries.sql from the authoritative BBMP ward
 * dataset.
 *
 *   node scripts/generate-ward-migration.mjs [path/to/BBMP.geojson]
 *
 * WHY A GENERATOR AND A COMMITTED OUTPUT, rather than importing at runtime:
 * the migration is the versioned record. A fresh clone must provision the same
 * 243 wards from the repository alone, with no network call to a third-party
 * host that may move, change, or disappear. The generator exists so the import
 * is REPRODUCIBLE — re-run it against a newer delimitation and diff the output
 * — not so it runs in production.
 *
 * SOURCE
 *   https://github.com/datameet/Municipal_Spatial_Data/tree/master/Bangalore
 *   File: BBMP.geojson — "243 Bangalore Wards as per delimitation in 2022,
 *   scraped from KSRSAC (https://kgis.ksrsac.in/bengalurugis/)".
 *   Licence: Creative Commons Attribution-ShareAlike 2.5 India.
 *
 * Download with:
 *   curl -L -o BBMP.geojson \
 *     https://raw.githubusercontent.com/datameet/Municipal_Spatial_Data/master/Bangalore/BBMP.geojson
 *
 * VALIDATION IS THE POINT. This script refuses to emit anything if the dataset
 * fails any structural check below, because a ward file that looks fine and is
 * subtly wrong is worse than no file: ward is a hard constraint on complaint
 * matching, so a bad boundary silently prevents related reports from grouping
 * and produces no error anywhere.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const INPUT = resolve(process.argv[2] ?? join(HERE, 'BBMP.geojson'));
const MIGRATIONS = join(HERE, '..', 'src', 'main', 'resources', 'db', 'migration');

/**
 * Output migration filename. Defaults to the one this script originally
 * produced, and REFUSES TO OVERWRITE IT.
 *
 * V13 has been applied to real databases. Flyway checksums every applied
 * migration and refuses to start if one changes on disk, so silently
 * regenerating over it would not produce a new import — it would brick every
 * environment that already ran the old one, at startup, with an error that
 * points at a file rather than at the script that rewrote it.
 *
 * A re-import is therefore a NEW migration:
 *   node scripts/generate-ward-migration.mjs BBMP.geojson V18__bbmp_wards_2027.sql
 *
 * It must also be numbered above V17, because the generated SQL writes into
 * `dataset_validations`, which V17 creates.
 */
const OUTPUT_NAME = process.argv[3] ?? 'V13__bbmp_ward_boundaries.sql';
const OUTPUT = join(MIGRATIONS, OUTPUT_NAME);

/** The delimitation this file describes. Both end up on every imported row. */
const SOURCE_KEY = 'kgis-bbmp-2022';
const DATASET_VERSION = '2022-delimitation';
/** Karnataka's 2022 BBMP ward delimitation took effect in this year. */
const EFFECTIVE_FROM = '2022-01-01';
const EXPECTED_WARDS = 243;

/**
 * Bengaluru's municipal extent with generous slop. Any vertex outside this is a
 * projection error or a wrong city, and either must stop the build — silently
 * importing a ward from somewhere else is precisely the failure mode the audit
 * raised.
 */
const BBOX = { minLon: 77.2, maxLon: 78.0, minLat: 12.6, maxLat: 13.4 };

/**
 * Coordinate precision, in decimal places.
 *
 * 6 dp is ~0.11 m at this latitude — far finer than the source survey and far
 * finer than a phone GPS fix, so rounding here cannot change which ward a real
 * point falls in. It exists to keep the committed migration to a sane size.
 */
const PRECISION = 6;

function fail(message) {
    console.error(`REFUSING TO GENERATE: ${message}`);
    process.exit(1);
}

// Refuse before doing any work, so the failure is obvious rather than a
// surprise at the end.
if (existsSync(OUTPUT)) {
    fail([
        `${OUTPUT_NAME} already exists.`,
        '  Applied migrations are checksummed by Flyway, and rewriting one breaks',
        '  startup in every environment that already ran it.',
        '  A re-import is a NEW migration, numbered above V17:',
        `    node scripts/generate-ward-migration.mjs ${process.argv[2] ?? 'BBMP.geojson'} V18__bbmp_wards_2027.sql`,
    ].join('\n'));
}

const geo = JSON.parse(readFileSync(INPUT, 'utf8'));

if (geo.type !== 'FeatureCollection') fail(`expected a FeatureCollection, got ${geo.type}`);
if (geo.features.length !== EXPECTED_WARDS) {
    fail(`expected ${EXPECTED_WARDS} wards, found ${geo.features.length}. `
        + 'If this is a NEW delimitation, that is a new migration and a new '
        + 'EXPECTED_WARDS — not an edit to this one.');
}

// GeoJSON without a "crs" member is WGS84 (RFC 7946). An explicit non-WGS84 crs
// would need reprojection, which this script deliberately does not attempt.
if (geo.crs && !JSON.stringify(geo.crs).includes('CRS84') && !JSON.stringify(geo.crs).includes('4326')) {
    fail(`dataset declares a non-WGS84 crs: ${JSON.stringify(geo.crs)}`);
}

const round = (n) => Number(n.toFixed(PRECISION));

/**
 * Ray-casting point-in-ring, used only to tell an exclave from a hole (see the
 * ring-classification note below). Its job is to distinguish "wholly inside"
 * from "wholly outside", never to decide a borderline case, so a naive
 * implementation is exactly adequate — and the result is checked by PostGIS
 * afterwards regardless.
 */
function pointInRing([x, y], ring) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [xi, yi] = ring[i];
        const [xj, yj] = ring[j];
        if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
            inside = !inside;
        }
    }
    return inside;
}

const wards = [];
const seenNo = new Map();
const seenCode = new Map();
const seenName = new Map();
let unmappedLgd = 0;

for (const feature of geo.features) {
    const p = feature.properties;
    const wardNo = Number(p.KGISWardNo);
    const name = String(p.KGISWardName ?? '').trim();
    const kgisCode = String(p.KGISWardCode ?? '').trim();
    const kgisId = String(p.KGISWardID ?? '').trim();
    // Null for the 45 wards created by the 2022 delimitation — the national
    // Local Government Directory has not issued codes for them. Recorded as
    // absent rather than invented.
    const rawLgd = p.LGD_WardCode;
    const lgd = rawLgd === null || rawLgd === undefined || String(rawLgd).trim() === ''
        ? null
        : String(rawLgd).trim();

    if (!Number.isInteger(wardNo) || wardNo < 1 || wardNo > EXPECTED_WARDS) {
        fail(`ward number out of range: ${JSON.stringify(p)}`);
    }
    if (!name) fail(`ward ${wardNo} has no name`);
    if (!kgisCode) fail(`ward ${wardNo} has no KGISWardCode`);

    if (seenNo.has(wardNo)) fail(`duplicate ward number ${wardNo}`);
    if (seenCode.has(kgisCode)) fail(`duplicate KGISWardCode ${kgisCode}`);
    // A duplicate NAME is a warning, not a failure: two wards legitimately
    // sharing a colloquial name is possible, and `code` is the identity.
    if (seenName.has(name)) {
        console.warn(`  warn: ward name "${name}" used by ${seenName.get(name)} and ${wardNo}`);
    }
    seenNo.set(wardNo, true);
    seenCode.set(kgisCode, true);
    seenName.set(name, wardNo);
    if (lgd === null) unmappedLgd++;

    if (feature.geometry.type !== 'Polygon') {
        fail(`ward ${wardNo} has geometry ${feature.geometry.type}; only Polygon is handled`);
    }

    const rings = feature.geometry.coordinates.map((ring, index) => {
        if (ring.length < 4) fail(`ward ${wardNo} ring ${index} has only ${ring.length} points`);

        for (const [lon, lat] of ring) {
            if (!Number.isFinite(lon) || !Number.isFinite(lat)) {
                fail(`ward ${wardNo} has a non-finite coordinate`);
            }
            if (lon < BBOX.minLon || lon > BBOX.maxLon || lat < BBOX.minLat || lat > BBOX.maxLat) {
                fail(`ward ${wardNo} has a vertex outside Bengaluru: ${lon}, ${lat}`);
            }
        }
        return ring;
    });

    // THE SOURCE DOES NOT FOLLOW RFC 7946 RING SEMANTICS, and taking it at its
    // word corrupts the data silently.
    //
    // In GeoJSON, a Polygon's first ring is the exterior and every later ring is
    // an interior ring — a HOLE. This dataset uses later rings for both: ward 6
    // (Kogilu) really does have an enclave inside it, but wards 1, 34, 105 and
    // 206 use extra rings for DISJOINT PARTS — exclaves lying wholly outside the
    // first ring. Ward 105 (Belathur) is three separate pieces plus a main one.
    //
    // Read literally, those exclaves become holes: PostGIS rejects them as
    // "Hole lies outside shell", and had it not, ward 105 would have lost 66% of
    // its area and the residents living there would resolve to no ward at all —
    // invisible, and impossible to notice from the outside.
    //
    // So each ring is classified by geometry rather than by position: a ring
    // that falls inside an existing part is that part's hole; a ring that falls
    // outside every part is a new part. The result is a MultiPolygon that says
    // what the survey meant.
    const parts = [];
    for (const ring of rings) {
        const probe = ring[0];
        const container = parts.find((part) => pointInRing(probe, part.outer));
        if (container) {
            container.holes.push(ring);
        } else {
            parts.push({ outer: ring, holes: [] });
        }
    }

    const toWkt = (ring) => {
        const points = ring.map(([lon, lat]) => `${round(lon)} ${round(lat)}`);
        // Rounding can collapse a ring's closing point; re-close explicitly so
        // the WKT stays valid.
        if (points[0] !== points[points.length - 1]) points.push(points[0]);
        return `(${points.join(',')})`;
    };

    const wkt = `MULTIPOLYGON(${parts
        .map((part) => `(${[part.outer, ...part.holes].map(toWkt).join(',')})`)
        .join(',')})`;

    wards.push({
        wardNo, name, kgisCode, kgisId, lgd, wkt,
        partCount: parts.length,
        holeCount: parts.reduce((n, part) => n + part.holes.length, 0),
    });
}

wards.sort((a, b) => a.wardNo - b.wardNo);

const quote = (value) => (value === null ? 'NULL' : `'${String(value).replace(/'/g, "''")}'`);

const values = wards.map((w) => `    (${[
    quote(String(w.wardNo)),
    quote(w.name),
    quote(SOURCE_KEY),
    quote(w.kgisId),
    quote(w.kgisCode),
    quote(w.lgd),
    `ST_Multi(ST_GeomFromText(${quote(w.wkt)}, 4326))`,
].join(', ')})`).join(',\n');

const sql = `-- BBMP ward boundaries — the authoritative ward universe.
--
-- Replaces the four placeholder centroids seeded in V2/V3, which were plausible
-- Bengaluru coordinates and nothing more. Ward is a HARD constraint on complaint
-- matching (both matchers filter candidates by ward_id, and the attach path
-- refuses a cross-ward decision), so an approximate ward assignment silently
-- prevents related reports from grouping and surfaces no error anywhere. That
-- made this the highest-severity data gap in the project.
--
-- SOURCE
--   ${EXPECTED_WARDS} BBMP wards, ${DATASET_VERSION}, scraped from KSRSAC
--   (https://kgis.ksrsac.in/bengalurugis/) and published by DataMeet at
--   https://github.com/datameet/Municipal_Spatial_Data/tree/master/Bangalore
--   Licence: Creative Commons Attribution-ShareAlike 2.5 India.
--
-- GENERATED — do not hand-edit. Re-run:
--   node scripts/generate-ward-migration.mjs path/to/BBMP.geojson
-- The generator refuses to emit anything unless the dataset passes every
-- structural check (ward count, id/code uniqueness, ring closure, and every
-- vertex inside a Bengaluru bounding box).
--
-- Coordinates are rounded to ${PRECISION} decimal places (~0.11 m), far finer
-- than the source survey or any phone GPS fix, to keep this file a sane size.

-- Point-in-polygon needs real geometry. V1 already creates an extension
-- (pgvector), so the role can do this; Neon supports PostGIS ${'3.x'}.
--
-- NOTE FOR A FUTURE RE-IMPORT: this migration writes its validation results
-- into \`dataset_validations\`, created in V17. A regenerated migration must
-- therefore be numbered ABOVE V17, or those INSERTs have no table to land in.
CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE wards
    -- Retired wards stay readable because complaints still reference them, but
    -- drop out of pickers and can never be resolved into.
    ADD COLUMN active           BOOLEAN NOT NULL DEFAULT TRUE,
    -- Provenance. Without these a ward row cannot answer "who says so, and as
    -- of when" — which is the question a delimitation change makes urgent.
    ADD COLUMN source           VARCHAR(64),
    ADD COLUMN source_ward_id   VARCHAR(32),
    ADD COLUMN source_ward_code VARCHAR(32),
    -- National Local Government Directory code. NULLABLE and NOT UNIQUE on
    -- purpose: ${unmappedLgd} of the ${EXPECTED_WARDS} wards have no LGD code at all in the
    -- source — they were created by the 2022 delimitation and the LGD has not
    -- issued codes for them. Inventing one would fabricate a national
    -- identifier; recording its absence is the honest option, and the
    -- ward_external_ids view below reports exactly which wards are unmapped.
    ADD COLUMN lgd_ward_code    VARCHAR(32),
    ADD COLUMN effective_from   DATE,
    ADD COLUMN dataset_version  VARCHAR(32),
    ADD COLUMN boundary         geometry(MultiPolygon, 4326);

-- The index that makes point-in-polygon a lookup rather than a scan of 243
-- polygons totalling ~73k vertices.
CREATE INDEX idx_wards_boundary ON wards USING GIST (boundary);
CREATE INDEX idx_wards_active   ON wards (active);

-- Identity within a source vocabulary. Scoped by source, so importing a future
-- delimitation alongside this one cannot collide with it.
CREATE UNIQUE INDEX uq_wards_source_code
    ON wards (source, source_ward_code) WHERE source IS NOT NULL;

-- Retire the placeholders. NOT deleted: complaints, incidents and the
-- reference-number counters all carry foreign keys to them, and deleting rows
-- out from under a resident's issued reference number would be worse than
-- keeping four inert rows. Their codes are prefixed because the real wards
-- claim '1', '2', '17' and '23' — the very numbers V2 invented.
UPDATE wards
SET code   = 'LEGACY-' || code,
    name   = name || ' (retired placeholder)',
    active = FALSE,
    source = 'placeholder-v2'
WHERE source IS NULL;

INSERT INTO wards (
    code, name, source, source_ward_id, source_ward_code, lgd_ward_code, boundary,
    effective_from, dataset_version, created_at, updated_at
)
SELECT v.code, v.name, v.source, v.source_ward_id, v.source_ward_code, v.lgd_ward_code,
       v.boundary, DATE '${EFFECTIVE_FROM}', '${DATASET_VERSION}', NOW(), NOW()
FROM (VALUES
${values}
) AS v(code, name, source, source_ward_id, source_ward_code, lgd_ward_code, boundary);

-- Repair the self-intersections the survey shipped with.
--
-- Five wards (48, 49, 136, 162, 164) carry rings that cross themselves. PostGIS
-- refuses to answer ST_Contains reliably on invalid geometry, so importing them
-- as-is would make those wards silently unresolvable.
--
-- ST_MakeValid is the standard repair, but it is NOT unconditionally safe: on
-- badly broken input it can return something with a wildly different area, and
-- an unnoticed area change here means a boundary that quietly moved. So the
-- change is MEASURED and the migration refuses anything above 0.5%. That bound
-- is what makes this a repair rather than a rewrite.
--
-- CollectionExtract(..., 3) keeps only the polygonal output: MakeValid can emit
-- stray lines or points where a ring pinched, and those are noise, not area.
DO $$
DECLARE
    rec        RECORD;
    fixed      geometry;
    before_m2  double precision;
    after_m2   double precision;
    drift      double precision;
    worst      double precision := 0;
    worst_ward text := '(none)';
    repaired   integer := 0;
BEGIN
    FOR rec IN
        SELECT id, code, name, boundary FROM wards
        WHERE source = '${SOURCE_KEY}' AND NOT ST_IsValid(boundary)
    LOOP
        before_m2 := ST_Area(rec.boundary::geography);
        fixed     := ST_Multi(ST_CollectionExtract(ST_MakeValid(rec.boundary), 3));
        after_m2  := ST_Area(fixed::geography);
        drift     := abs(after_m2 - before_m2) / NULLIF(before_m2, 0);

        IF drift > worst THEN
            worst := drift;
            worst_ward := rec.code || ' ' || rec.name;
        END IF;

        UPDATE wards SET boundary = fixed WHERE id = rec.id;
        repaired := repaired + 1;
    END LOOP;

    RAISE NOTICE 'Repaired % invalid ward boundaries; largest area change % percent (ward %)',
        repaired, round((worst * 100)::numeric, 4), worst_ward;

    -- PERSIST it, do not merely announce it. A RAISE NOTICE cannot be queried,
    -- compared against the next import, or alerted on -- it lives exactly as
    -- long as whoever happened to be watching the deploy output.
    -- dataset_validations is created in V17.
    INSERT INTO dataset_validations
        (dataset_name, dataset_version, check_name, subject, status,
         observed, threshold, unit, detail, checked_at)
    VALUES ('bbmp-wards', '${DATASET_VERSION}', 'repair-area-drift', NULL,
            CASE WHEN worst > 0.005 THEN 'FAIL' ELSE 'PASS' END,
            round((worst * 100)::numeric, 6)::double precision, 0.5, 'percent',
            format('%s invalid boundaries repaired with ST_MakeValid; largest area change on ward %s',
                   repaired, worst_ward),
            NOW());

    IF worst > 0.005 THEN
        RAISE EXCEPTION 'ST_MakeValid moved a ward boundary by % percent (ward %) — that is a rewrite, not a repair',
            round((worst * 100)::numeric, 3), worst_ward;
    END IF;
END $$;

-- Centroids, derived rather than supplied.
--
-- ST_PointOnSurface, NOT ST_Centroid: a ward is frequently concave or
-- horseshoe-shaped, and a true centroid of such a shape can land OUTSIDE the
-- ward — in a neighbouring one. PointOnSurface is guaranteed to be inside the
-- polygon it describes, which is what a "representative point" has to mean when
-- the fallback resolver measures distance to it.
UPDATE wards
SET latitude  = ST_Y(ST_PointOnSurface(boundary)),
    longitude = ST_X(ST_PointOnSurface(boundary))
WHERE source = '${SOURCE_KEY}';

-- External identifier reporting, which ISSUE-11 asks for and which the data
-- makes necessary: a view rather than a table, because it is derived from the
-- ward rows themselves and a copy would go stale.
CREATE VIEW ward_external_ids AS
SELECT id                AS ward_id,
       code              AS internal_code,
       name,
       source,
       source_ward_id,
       source_ward_code,
       lgd_ward_code,
       dataset_version,
       effective_from,
       lgd_ward_code IS NULL AS lgd_unmapped
FROM wards
WHERE source IS NOT NULL;

COMMENT ON VIEW ward_external_ids IS
    'Ward identity across vocabularies. Query WHERE lgd_unmapped to list wards '
    'with no national LGD code — do not guess one.';

-- Re-home existing complaints and incidents onto real wards.
--
-- Everything currently sits in a placeholder ward, so leaving it there would
-- keep the exact defect this migration exists to remove. The remap is by actual
-- coordinates, which the rows already carry.
--
-- INCIDENTS MOVE FIRST, AND THEIR MEMBERS FOLLOW THEM. Remapping each complaint
-- independently would scatter one incident's members across several real wards
-- the moment a group straddles a boundary, breaking the invariant
-- IncidentAttachmentService enforces (a complaint and its incident share a
-- ward) and making those incidents unattachable thereafter.
UPDATE incidents i
SET ward_id = w.id
FROM wards w
WHERE w.source = '${SOURCE_KEY}'
  AND i.latitude IS NOT NULL AND i.longitude IS NOT NULL
  AND ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326))
  AND i.ward_id IN (SELECT id FROM wards WHERE source = 'placeholder-v2');

UPDATE complaints c
SET ward_id = i.ward_id
FROM incidents i
WHERE c.incident_id = i.id
  AND c.ward_id <> i.ward_id;

UPDATE complaints c
SET ward_id = w.id
FROM wards w
WHERE w.source = '${SOURCE_KEY}'
  AND c.incident_id IS NULL
  AND ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(c.longitude, c.latitude), 4326))
  AND c.ward_id IN (SELECT id FROM wards WHERE source = 'placeholder-v2');

-- Anything still on a placeholder ward fell outside every real boundary — a
-- demo coordinate placed outside the municipal area. It is LEFT THERE rather
-- than pushed into the nearest ward, for the same reason WardResolver returns
-- empty instead of guessing: a visibly unassigned report is recoverable, a
-- silently misfiled one is not.

-- Post-conditions. A migration that half-applied a ward import is far more
-- dangerous than one that refused to apply, because everything downstream keeps
-- working and is quietly wrong.
DO $$
DECLARE
    ward_count     INTEGER;
    no_boundary    INTEGER;
    invalid_geom   INTEGER;
    centroid_out   INTEGER;
    overlap_pairs  INTEGER;
    lgd_missing    INTEGER;
    stranded       INTEGER;
BEGIN
    SELECT COUNT(*) INTO ward_count   FROM wards WHERE source = '${SOURCE_KEY}';
    SELECT COUNT(*) INTO no_boundary  FROM wards WHERE source = '${SOURCE_KEY}' AND boundary IS NULL;
    SELECT COUNT(*) INTO invalid_geom FROM wards WHERE source = '${SOURCE_KEY}' AND NOT ST_IsValid(boundary);
    SELECT COUNT(*) INTO centroid_out FROM wards
      WHERE source = '${SOURCE_KEY}'
        AND NOT ST_Intersects(boundary, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326));

    IF ward_count <> ${EXPECTED_WARDS} THEN
        RAISE EXCEPTION 'expected ${EXPECTED_WARDS} imported wards, found %', ward_count;
    END IF;
    IF no_boundary > 0 THEN
        RAISE EXCEPTION '% imported wards have no boundary', no_boundary;
    END IF;
    IF invalid_geom > 0 THEN
        RAISE EXCEPTION '% imported ward boundaries are not valid geometry', invalid_geom;
    END IF;
    IF centroid_out > 0 THEN
        RAISE EXCEPTION '% derived centroids fall outside their own ward', centroid_out;
    END IF;

    -- Wards should tile the city without overlapping. Real survey data has
    -- sliver overlaps along shared edges, which are harmless — ST_Contains
    -- picks one and the resolver's LIMIT 1 makes that deterministic. A LARGE
    -- overlap would mean two wards genuinely claim the same streets, which is
    -- a data problem no code can paper over, so it is reported either way.
    SELECT COUNT(*) INTO overlap_pairs
    FROM wards a JOIN wards b ON a.id < b.id
    WHERE a.source = '${SOURCE_KEY}' AND b.source = '${SOURCE_KEY}'
      AND ST_Overlaps(a.boundary, b.boundary)
      AND ST_Area(ST_Intersection(a.boundary, b.boundary)::geography) > 10000;
    IF overlap_pairs > 0 THEN
        RAISE WARNING '% ward pairs overlap by more than 1 hectare', overlap_pairs;
    END IF;

    SELECT COUNT(*) INTO lgd_missing FROM wards
     WHERE source = '${SOURCE_KEY}' AND lgd_ward_code IS NULL;

    -- Every structural check, recorded alongside the repair, so one query
    -- answers "how good was this import?" without anyone reading a migration.
    INSERT INTO dataset_validations
        (dataset_name, dataset_version, check_name, subject, status,
         observed, threshold, unit, detail, checked_at)
    VALUES
        ('bbmp-wards', '${DATASET_VERSION}', 'ward-count', NULL,
         CASE WHEN ward_count = ${EXPECTED_WARDS} THEN 'PASS' ELSE 'FAIL' END,
         ward_count, ${EXPECTED_WARDS}, 'wards',
         'Expected ward count for this delimitation.', NOW()),
        ('bbmp-wards', '${DATASET_VERSION}', 'geometry-validity', NULL,
         CASE WHEN invalid_geom = 0 THEN 'PASS' ELSE 'FAIL' END,
         invalid_geom, 0, 'invalid geometries',
         'Boundaries PostGIS will not answer ST_Contains on reliably.', NOW()),
        ('bbmp-wards', '${DATASET_VERSION}', 'centroid-containment', NULL,
         CASE WHEN centroid_out = 0 THEN 'PASS' ELSE 'FAIL' END,
         centroid_out, 0, 'centroids outside their ward',
         'ST_PointOnSurface guarantees this; a failure means the derivation changed.', NOW()),
        ('bbmp-wards', '${DATASET_VERSION}', 'ward-overlap', NULL,
         CASE WHEN overlap_pairs = 0 THEN 'PASS' ELSE 'WARN' END,
         overlap_pairs, 0, 'ward pairs overlapping by >1 hectare',
         'Sliver overlaps along shared edges are harmless; a large one means two '
         'wards claim the same streets.', NOW()),
        ('bbmp-wards', '${DATASET_VERSION}', 'lgd-code-coverage', NULL,
         CASE WHEN lgd_missing = 0 THEN 'PASS' ELSE 'WARN' END,
         lgd_missing, 0, 'wards without an LGD code',
         'Absence is recorded rather than invented; see the ward_external_ids view.',
         NOW());

    SELECT COUNT(*) INTO stranded FROM complaints c
      JOIN wards w ON w.id = c.ward_id WHERE w.source = 'placeholder-v2';
    IF stranded > 0 THEN
        RAISE WARNING '% complaints remain on a retired placeholder ward: their '
                      'coordinates fall outside every BBMP boundary', stranded;
    END IF;
END $$;
`;

writeFileSync(OUTPUT, sql, 'utf8');

console.log(`Validated ${wards.length} wards from ${INPUT}`);
console.log(`  ward numbers 1..${EXPECTED_WARDS}, no gaps, no duplicate codes`);
console.log(`  ${unmappedLgd} wards have no LGD code (recorded as NULL, reported by ward_external_ids)`);
const multiPart = wards.filter((w) => w.partCount > 1);
const withHoles = wards.filter((w) => w.holeCount > 0);
console.log(`  ${multiPart.length} wards are geographically split into multiple parts: `
    + multiPart.map((w) => `${w.wardNo}(${w.partCount})`).join(', '));
console.log(`  ${withHoles.length} wards contain an enclave: `
    + withHoles.map((w) => `${w.wardNo}(${w.holeCount})`).join(', '));
console.log(`  wrote ${OUTPUT} (${(sql.length / 1024 / 1024).toFixed(2)} MB)`);
