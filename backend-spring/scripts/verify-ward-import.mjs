/**
 * Verifies the imported ward universe against the live database.
 *
 *   node scripts/verify-ward-import.mjs
 *
 * Reads DATABASE_URL from backend-node/.env, the same source seed-dev-data.mjs
 * uses. READ-ONLY: it asserts, it never writes. Exits non-zero on any failure,
 * so it works as a CI gate after a ward import.
 *
 * This is the check the audit's ISSUE-05 acceptance criteria ask for — ward
 * universe chosen, every ward present, no duplicate ids or names, coordinates
 * validated, external ids deterministic — plus the boundary probes that
 * distinguish a working point-in-polygon lookup from a lucky one.
 */

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const springDir = join(here, '..');
// pg lives in backend-node, same as seed-dev-data.mjs. Resolved from there so
// this script runs from anywhere without backend-spring growing a package.json.
const require = createRequire(join(springDir, '..', 'backend-node', 'package.json'));
const pg = require('pg');

function envValue(file, key) {
    const match = readFileSync(file, 'utf8').match(new RegExp(`^${key}=(.*)$`, 'm'));
    return match ? match[1].trim() : null;
}

const DATABASE_URL = process.env.DATABASE_URL
    ?? envValue(join(springDir, '..', 'backend-node', '.env'), 'DATABASE_URL');
if (!DATABASE_URL) throw new Error('DATABASE_URL missing from backend-node/.env');

const pool = new pg.Pool({ connectionString: DATABASE_URL, connectionTimeoutMillis: 25000 });
const SOURCE = 'kgis-bbmp-2022';

let failures = 0;
function check(label, ok, detail = '') {
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
    if (!ok) failures++;
}

const one = async (sql, params = []) => (await pool.query(sql, params)).rows[0];

console.log('\nWard universe');
const u = await one(`
    SELECT COUNT(*)::int                                        AS total,
           COUNT(*) FILTER (WHERE boundary IS NULL)::int         AS no_boundary,
           COUNT(*) FILTER (WHERE NOT ST_IsValid(boundary))::int AS invalid,
           COUNT(DISTINCT code)::int                             AS distinct_codes,
           COUNT(DISTINCT name)::int                             AS distinct_names,
           COUNT(DISTINCT source_ward_code)::int                 AS distinct_source_codes,
           COUNT(*) FILTER (WHERE lgd_ward_code IS NULL)::int    AS lgd_unmapped,
           MIN(code::int)::int AS min_code, MAX(code::int)::int AS max_code
    FROM wards WHERE source = $1`, [SOURCE]);

check('243 wards imported', u.total === 243, `found ${u.total}`);
check('every ward has a boundary', u.no_boundary === 0, `${u.no_boundary} missing`);
check('every boundary is valid geometry', u.invalid === 0, `${u.invalid} invalid`);
check('ward codes are unique', u.distinct_codes === 243);
check('ward names are unique', u.distinct_names === 243);
check('source ward codes are unique', u.distinct_source_codes === 243);
check('codes run 1..243 with no gaps', u.min_code === 1 && u.max_code === 243);
console.log(`  INFO  ${u.lgd_unmapped} wards have no national LGD code (recorded, not invented)`);

console.log('\nCentroids');
const c = await one(`
    SELECT COUNT(*) FILTER (WHERE latitude IS NULL OR longitude IS NULL)::int AS missing,
           COUNT(*) FILTER (WHERE NOT ST_Intersects(
               boundary, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)))::int AS outside,
           COUNT(*) FILTER (WHERE latitude NOT BETWEEN 12.6 AND 13.4
                               OR longitude NOT BETWEEN 77.2 AND 78.0)::int AS out_of_city
    FROM wards WHERE source = $1`, [SOURCE]);
check('every ward has a centroid', c.missing === 0, `${c.missing} missing`);
check('every centroid lies inside its own ward', c.outside === 0, `${c.outside} outside`);
check('every centroid is within Bengaluru', c.out_of_city === 0, `${c.out_of_city} out of range`);

console.log('\nPoint-in-polygon (the behaviour nearest-centroid could not give)');
// A ward's own representative point must resolve to that ward, and to exactly
// one. Run over ALL 243 rather than a sample: the whole risk of centroid
// assignment was that it is right on average and wrong in particular places.
const selfResolve = await one(`
    SELECT COUNT(*)::int AS wrong FROM wards w
    WHERE w.source = $1 AND (
        SELECT COUNT(*) FROM wards x
        WHERE x.active AND ST_Contains(x.boundary,
            ST_SetSRID(ST_MakePoint(w.longitude, w.latitude), 4326))
    ) <> 1`, [SOURCE]);
check('all 243 representative points resolve to exactly one ward', selfResolve.wrong === 0,
    `${selfResolve.wrong} ambiguous or unresolved`);

// Outside the city must resolve to NOTHING. This is the property that makes a
// 404 honest rather than a silent mis-filing.
for (const [label, lat, lng] of [
    ['Mysuru (150 km away)', 12.2958, 76.6394],
    ['Chennai (290 km away)', 13.0827, 80.2707],
    ['Null Island', 0, 0],
    ['just outside the northern boundary', 13.30, 77.60],
]) {
    const r = await one(`
        SELECT COUNT(*)::int AS n FROM wards
        WHERE active AND ST_Contains(boundary, ST_SetSRID(ST_MakePoint($1, $2), 4326))`,
        [lng, lat]);
    check(`${label} resolves to no ward`, r.n === 0, `matched ${r.n}`);
}

console.log('\nMulti-part wards (the rings that would have been silently lost)');
for (const code of ['1', '34', '105', '206']) {
    const r = await one(
        `SELECT ST_NumGeometries(boundary)::int AS parts, name FROM wards WHERE source=$1 AND code=$2`,
        [SOURCE, code]);
    check(`ward ${code} (${r.name}) kept its separate parts`, r.parts > 1, `${r.parts} part(s)`);
}
const kogilu = await one(
    `SELECT ST_NumInteriorRings(ST_GeometryN(boundary,1))::int AS holes FROM wards WHERE source=$1 AND code='6'`,
    [SOURCE]);
check('ward 6 (Kogilu) kept its enclave as a hole', kogilu.holes === 1, `${kogilu.holes} hole(s)`);

console.log('\nExisting data re-homed');
const d = await one(`
    SELECT (SELECT COUNT(*)::int FROM complaints c JOIN wards w ON w.id=c.ward_id
              WHERE w.source='placeholder-v2')                                    AS stranded_complaints,
           (SELECT COUNT(*)::int FROM incidents i JOIN wards w ON w.id=i.ward_id
              WHERE w.source='placeholder-v2')                                    AS stranded_incidents,
           (SELECT COUNT(*)::int FROM complaints c JOIN incidents i ON i.id=c.incident_id
              WHERE c.ward_id <> i.ward_id)                                       AS ward_mismatch,
           (SELECT COUNT(DISTINCT ward_id)::int FROM complaints)                  AS wards_in_use,
           (SELECT COUNT(*)::int FROM wards WHERE NOT active)                     AS retired`);
check('no complaint left on a retired placeholder ward', d.stranded_complaints === 0,
    `${d.stranded_complaints} stranded`);
check('no incident left on a retired placeholder ward', d.stranded_incidents === 0,
    `${d.stranded_incidents} stranded`);
// The invariant IncidentAttachmentService enforces on every attach. If the
// remap had moved complaints independently of their incident, this would break
// and those incidents would reject every future complaint.
check('every complaint shares its incident\'s ward', d.ward_mismatch === 0,
    `${d.ward_mismatch} mismatched`);
console.log(`  INFO  complaints now spread across ${d.wards_in_use} real wards`);
console.log(`  INFO  ${d.retired} placeholder wards retired (kept for referential integrity)`);

console.log('\nComplaints land in the ward their coordinates actually fall in');
const misfiled = await one(`
    SELECT COUNT(*)::int AS n FROM complaints c
    JOIN wards w ON w.id = c.ward_id
    WHERE w.source = $1
      AND NOT ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(c.longitude, c.latitude), 4326))
      AND c.incident_id IS NULL`, [SOURCE]);
check('no ungrouped complaint sits outside its own ward', misfiled.n === 0, `${misfiled.n} misfiled`);

console.log('\nExternal identifier view');
// The view covers every ward carrying a source, which is the 243 imported ones
// PLUS the 4 retired placeholders — those are tagged 'placeholder-v2' precisely
// so their provenance is stated rather than implied by a null.
const v = await one(`SELECT COUNT(*)::int AS rows,
                            COUNT(*) FILTER (WHERE source = $1)::int AS imported,
                            COUNT(*) FILTER (WHERE lgd_unmapped AND source = $1)::int AS unmapped,
                            COUNT(DISTINCT source_ward_code) FILTER (WHERE source = $1)::int AS codes
                     FROM ward_external_ids`, [SOURCE]);
check('ward_external_ids covers every ward with a provenance', v.rows === 247, `${v.rows} rows`);
check('the view exposes all 243 imported wards', v.imported === 243, `${v.imported}`);
check('every imported ward maps to a distinct source code', v.codes === 243, `${v.codes} distinct`);
check('unmapped LGD codes are reported, not hidden', v.unmapped === 45, `${v.unmapped} reported`);

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}\n`);
await pool.end();
process.exit(failures === 0 ? 0 : 1);
