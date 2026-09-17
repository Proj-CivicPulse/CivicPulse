/**
 * End-to-end verification of the ingestion gate — ISSUE-10.
 *
 *   # with backend-spring running on :8080
 *   node scripts/verify-ingestion.mjs
 *
 * Exercises the acceptance criteria against the REAL endpoint and the REAL
 * database, rather than against mocks:
 *
 *   - invalid records never reach the complaints table
 *   - every rejected record carries machine-readable reasons
 *   - source identifiers are preserved
 *   - re-running the same input changes nothing (idempotency)
 *   - an upstream edit updates in place instead of duplicating
 *   - metrics are visible afterwards
 *
 * It tags everything it creates with a throwaway source and DELETES it at the
 * end, so running this against a development database leaves no residue.
 */

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const springDir = join(here, '..');
const require = createRequire(join(springDir, '..', 'backend-node', 'package.json'));
const pg = require('pg');

function envValue(file, key) {
    const match = readFileSync(file, 'utf8').match(new RegExp(`^${key}=(.*)$`, 'm'));
    return match ? match[1].trim() : null;
}

const API = process.env.INGEST_API ?? 'http://localhost:8080';
const INTERNAL_TOKEN = envValue(join(springDir, '.env'), 'INTERNAL_TOKEN');
const DATABASE_URL = envValue(join(springDir, '..', 'backend-node', '.env'), 'DATABASE_URL');
const SOURCE = 'verify-probe';

if (!INTERNAL_TOKEN) throw new Error('INTERNAL_TOKEN missing from backend-spring/.env');

const pool = new pg.Pool({ connectionString: DATABASE_URL, connectionTimeoutMillis: 25000 });

let failures = 0;
function check(label, ok, detail = '') {
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
    if (!ok) failures++;
}

async function ingest(records) {
    const response = await fetch(`${API}/internal/ingest/${SOURCE}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Internal-Token': INTERNAL_TOKEN },
        body: JSON.stringify(records),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(`${response.status} ${JSON.stringify(body)}`);
    return body;
}

const good = (id, extra = {}) => ({
    sourceRecordId: id,
    description: `Verification probe ${id}: pothole outside the school gate`,
    category: 'Road Damage',           // an alias, not the canonical code
    lat: '12.9250',
    lng: '77.5938',
    reportedAt: '2026-09-10T08:30:00Z',
    status: 'REGISTERED',              // the feed's vocabulary, not ours
    ...extra,
});

/* ------------------------------------------------------------------ run */

console.log('\nCleaning up any residue from a previous run');
await pool.query(`DELETE FROM ingestion_records WHERE source = $1`, [SOURCE]);
await pool.query(`DELETE FROM ingestion_batches WHERE source = $1`, [SOURCE]);
await pool.query(
    `DELETE FROM incident_match_log WHERE complaint_id IN
       (SELECT id FROM complaints WHERE source = $1)`, [SOURCE]);
await pool.query(
    `UPDATE complaints SET incident_id = NULL WHERE source = $1`, [SOURCE]);
await pool.query(
    `DELETE FROM incidents WHERE id NOT IN (SELECT DISTINCT incident_id FROM complaints
       WHERE incident_id IS NOT NULL) AND complaint_count <= 1
       AND created_at > NOW() - INTERVAL '1 hour'`);
await pool.query(`DELETE FROM complaints WHERE source = $1`, [SOURCE]);

console.log('\nBatch 1 — a realistic mix of good and broken records');
const batch1 = [
    good('P-001'),
    good('P-002', { category: 'Uncollected Garbage', lat: '12.9716', lng: '77.5946' }),
    // Everything that can be wrong with one record.
    { sourceRecordId: 'P-BAD-1', description: '  ', category: 'interpretive dance',
      lat: 'twelve', lng: '999', reportedAt: 'last Tuesday', status: 'QUANTUM' },
    // A failed geocode upstream.
    good('P-BAD-2', { lat: '0', lng: '0' }),
    // Outside the municipal area entirely.
    good('P-BAD-3', { lat: '12.2958', lng: '76.6394' }),
    // No upstream identifier at all.
    good('P-BAD-4', { sourceRecordId: null }),
    // The same id twice inside one delivery.
    good('P-001'),
];

const report1 = await ingest(batch1);
console.log('  report:', JSON.stringify(report1));
check('received every record', report1.received === 7, `${report1.received}`);
check('accepted only the valid ones', report1.accepted === 2, `${report1.accepted}`);
check('rejected the four broken ones', report1.rejected === 4, `${report1.rejected}`);
check('caught the in-batch duplicate', report1.unchanged === 1, `${report1.unchanged}`);

console.log('\nInvalid records never reached the complaints table');
const landed = await pool.query(
    `SELECT source_record_id FROM complaints WHERE source = $1 ORDER BY source_record_id`, [SOURCE]);
check('exactly the two valid records exist', landed.rowCount === 2, `${landed.rowCount}`);
check('source identifiers preserved',
    landed.rows.map((r) => r.source_record_id).join(',') === 'P-001,P-002',
    landed.rows.map((r) => r.source_record_id).join(','));

console.log('\nEvery rejection carries reasons');
const reasons = await pool.query(
    `SELECT source_record_id, jsonb_array_length(rejection_reasons) AS n
     FROM ingestion_records WHERE source = $1 AND status = 'REJECTED'
     ORDER BY source_record_id`, [SOURCE]);
check('four rejected records recorded', reasons.rowCount === 4, `${reasons.rowCount}`);
check('none of them has an empty reason list',
    reasons.rows.every((r) => Number(r.n) > 0),
    reasons.rows.map((r) => `${r.source_record_id}:${r.n}`).join(' '));

const multi = reasons.rows.find((r) => r.source_record_id === 'P-BAD-1');
check('the multiply-broken record reports ALL its faults, not just the first',
    Number(multi.n) >= 6, `${multi.n} reasons`);

console.log('\nRaw payloads preserved for the rejected records');
const raw = await pool.query(
    `SELECT raw FROM ingestion_records WHERE source = $1 AND source_record_id = 'P-BAD-1'`, [SOURCE]);
check('the offending payload is stored verbatim',
    raw.rows[0]?.raw?.lat === 'twelve', JSON.stringify(raw.rows[0]?.raw?.lat));

console.log('\nCategories were normalised on the way in');
const cats = await pool.query(
    `SELECT source_record_id, category, source_category FROM complaints
     WHERE source = $1 ORDER BY source_record_id`, [SOURCE]);
check('"Road Damage" stored as canonical "pothole"',
    cats.rows[0].category === 'pothole' && cats.rows[0].source_category === 'Road Damage',
    `${cats.rows[0].category} / ${cats.rows[0].source_category}`);
check('"Uncollected Garbage" stored as canonical "garbage"',
    cats.rows[1].category === 'garbage' && cats.rows[1].source_category === 'Uncollected Garbage',
    `${cats.rows[1].category} / ${cats.rows[1].source_category}`);

console.log('\nWards resolved by point-in-polygon, not guessed');
const wards = await pool.query(
    `SELECT c.source_record_id, w.code, w.name, w.source
     FROM complaints c JOIN wards w ON w.id = c.ward_id
     WHERE c.source = $1 ORDER BY c.source_record_id`, [SOURCE]);
check('both landed in real BBMP wards',
    wards.rows.every((r) => r.source === 'kgis-bbmp-2022'),
    wards.rows.map((r) => `${r.code} ${r.name}`).join(' | '));

console.log('\nUpstream timestamps kept, not overwritten with import time');
// created_at::text, NOT the driver's Date. `timestamp without time zone` is
// parsed by node-postgres in the PROCESS's local zone, so a correct 08:30 reads
// back as 03:00Z on a machine set to IST — which looks exactly like the bug
// this check exists to catch. Comparing the raw text removes the ambiguity.
const times = await pool.query(
    `SELECT created_at::text AS stored FROM complaints
     WHERE source = $1 AND source_record_id = 'P-001'`, [SOURCE]);
check('created_at is the upstream reported time, not the import moment',
    times.rows[0].stored.startsWith('2026-09-10 08:30'),
    times.rows[0].stored);

console.log('\nBatch 2 — the same file again (idempotency)');
const report2 = await ingest(batch1);
console.log('  report:', JSON.stringify(report2));
check('nothing new was accepted', report2.accepted === 0, `${report2.accepted}`);
check('the valid records came back as unchanged', report2.unchanged === 3, `${report2.unchanged}`);

const afterRerun = await pool.query(
    `SELECT COUNT(*)::int AS n FROM complaints WHERE source = $1`, [SOURCE]);
check('the complaints table did not grow', afterRerun.rows[0].n === 2, `${afterRerun.rows[0].n}`);

console.log('\nBatch 3 — one record edited upstream');
const report3 = await ingest([good('P-001', { description: 'Edited upstream: the pothole is worse' })]);
console.log('  report:', JSON.stringify(report3));
check('the edit was applied as an update', report3.accepted === 1, `${report3.accepted}`);

const edited = await pool.query(
    `SELECT description FROM complaints WHERE source = $1 AND source_record_id = 'P-001'`, [SOURCE]);
check('the existing complaint was updated in place',
    edited.rows[0].description.startsWith('Edited upstream'), edited.rows[0].description);

const stillTwo = await pool.query(
    `SELECT COUNT(*)::int AS n FROM complaints WHERE source = $1`, [SOURCE]);
check('an update did not create a twin', stillTwo.rows[0].n === 2, `${stillTwo.rows[0].n}`);

console.log('\nMetrics are visible after the fact');
const batches = await pool.query(
    `SELECT received, accepted, rejected, duplicates FROM ingestion_batches
     WHERE source = $1 ORDER BY id`, [SOURCE]);
check('every run recorded its counts', batches.rowCount === 3, `${batches.rowCount} batches`);

const view = await pool.query(
    `SELECT field, code, occurrences FROM ingestion_rejections
     WHERE source = $1 ORDER BY occurrences DESC, field`, [SOURCE]);
check('the rejection report groups faults by field and code', view.rowCount > 0,
    `${view.rowCount} distinct faults`);
console.log('  rejection report:');
for (const r of view.rows) console.log(`    ${r.field.padEnd(16)} ${r.code.padEnd(20)} x${r.occurrences}`);

/* -------------------------------------------------------------- cleanup */

console.log('\nCleaning up');

// Matching is ASYNCHRONOUS: the complaints this script created were handed to
// the matcher after their response was sent, and it may still be attaching them
// to incidents right now. Deleting first and sweeping second leaves whatever
// landed in between — which is exactly what happened the first time this ran.
// Wait for the pipeline to settle, with a bound so a dead matcher cannot hang
// the cleanup.
for (let i = 0; i < 20; i++) {
    const pending = await pool.query(
        `SELECT COUNT(*)::int AS n FROM complaints
         WHERE source = $1 AND matching_status IN ('PENDING', 'PROCESSING')`, [SOURCE]);
    if (pending.rows[0].n === 0) break;
    await new Promise((r) => setTimeout(r, 1000));
}

await pool.query(`DELETE FROM ingestion_records WHERE source = $1`, [SOURCE]);
await pool.query(`DELETE FROM ingestion_batches WHERE source = $1`, [SOURCE]);
await pool.query(
    `DELETE FROM incident_match_log WHERE complaint_id IN
       (SELECT id FROM complaints WHERE source = $1)`, [SOURCE]);
await pool.query(`UPDATE complaints SET incident_id = NULL WHERE source = $1`, [SOURCE]);
await pool.query(`DELETE FROM complaints WHERE source = $1`, [SOURCE]);
// An incident with no members is always garbage, whoever created it. Sweeping
// by that property rather than by a list of ids captured earlier is what makes
// the cleanup immune to anything the matcher did while this script was running.
const empties = await pool.query(
    `SELECT id FROM incidents i
     WHERE NOT EXISTS (SELECT 1 FROM complaints c WHERE c.incident_id = i.id)`);
for (const row of empties.rows) {
    await pool.query(
        `DELETE FROM incident_match_log
         WHERE chosen_incident_id = $1 OR previous_incident_id = $1`, [row.id]);
    await pool.query(`DELETE FROM incidents WHERE id = $1`, [row.id]);
}

const residue = await pool.query(
    `SELECT (SELECT COUNT(*)::int FROM complaints WHERE source = $1) AS complaints,
            (SELECT COUNT(*)::int FROM incidents i
               WHERE NOT EXISTS (SELECT 1 FROM complaints c WHERE c.incident_id = i.id))
              AS empty_incidents`, [SOURCE]);
check('no test complaints left behind', residue.rows[0].complaints === 0,
    `${residue.rows[0].complaints} rows`);
check('no orphaned incidents left behind', residue.rows[0].empty_incidents === 0,
    `${residue.rows[0].empty_incidents} incidents`);

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`}\n`);
await pool.end();
process.exit(failures === 0 ? 0 : 1);
