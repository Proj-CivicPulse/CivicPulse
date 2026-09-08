/**
 * Development seed data.
 *
 * NOT a Flyway migration on purpose — this is demo content, and a migration
 * would carry it into every environment including production. Run it by hand
 * against a dev database only.
 *
 *   node scripts/seed-dev-data.mjs
 *
 * WHY IT GOES THROUGH THE API rather than INSERTing rows:
 * reference numbers, ward derivation, incident grouping, and the priority score
 * with its reasons are all produced by real code paths. Writing rows directly
 * would mean reimplementing PriorityService in JavaScript, and the copy would
 * drift from the Java the moment either changed.
 *
 * SINCE PHASE 2, MATCHING IS ASYNCHRONOUS. POST /complaints returns immediately
 * with incidentId: null and matchingStatus: "pending" — grouping happens after
 * the response, in backend-node (or via the naive fallback if Node is down). So
 * this script creates every complaint first, then WAITS for the pipeline to
 * settle before it can know which incidents formed.
 *
 *   - backend-node running + EMBEDDING_API_KEY set  -> real semantic grouping.
 *     One hotspot may legitimately split into two or three incidents if its
 *     descriptions fall below MATCH_SIMILARITY_THRESHOLD. That is the matcher
 *     working, not a bug, and the summary below reports it — it is a cheap read
 *     on how the threshold is behaving.
 *   - backend-node down -> the naive fallback groups them and tags them
 *     `degraded`. The seed still works; the grouping is just the old baseline.
 *
 * The one thing the API cannot give us is AGE: everything it creates is new, so
 * the age term of the priority formula would be zero for every incident and the
 * whole queue would score the same. So the script backdates created_at in SQL,
 * then calls POST /internal/incidents/{id}/recompute for each incident, which
 * re-derives the score and reasons against the ages that now exist.
 *
 * It deliberately does NOT use /internal/incidents/attach for that. attach
 * records a matching DECISION — it stamps matching_status and appends an
 * incident_match_log row — so replaying it per incident would fabricate
 * "semantic match" entries that never happened and corrupt the dataset Phase 8
 * evaluates precision/recall on.
 *
 * Requires INTERNAL_TOKEN to be set in backend-spring/.env and the app running.
 */

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const springDir = join(here, '..');
// pg lives in backend-node; this script is the only consumer in this service.
const require = createRequire(join(springDir, '..', 'backend-node', 'package.json'));
const { Client } = require('pg');

const API = process.env.SEED_API ?? 'http://localhost:8080';

/**
 * How long to wait for asynchronous matching to finish before giving up.
 *
 * A full seed is ~150 complaints, each costing one embedding round trip through
 * backend-node. Generous on purpose: exceeding it is a warning, not a failure.
 */
const SETTLE_TIMEOUT_MS = Number(process.env.SEED_SETTLE_TIMEOUT_MS ?? 300_000);

function envValue(file, key) {
    const match = readFileSync(file, 'utf8').match(new RegExp(`^${key}=(.*)$`, 'm'));
    return match ? match[1].trim() : null;
}

const INTERNAL_TOKEN = envValue(join(springDir, '.env'), 'INTERNAL_TOKEN');
const DATABASE_URL = envValue(join(springDir, '..', 'backend-node', '.env'), 'DATABASE_URL');

if (!INTERNAL_TOKEN) throw new Error('INTERNAL_TOKEN missing from backend-spring/.env');
if (!DATABASE_URL) throw new Error('DATABASE_URL missing from backend-node/.env');

/* ------------------------------------------------------------------ data */

/**
 * One hotspot becomes one incident. Categories are unique per ward so the
 * ward+category grouper cannot accidentally merge two of them, and offsets stay
 * under ~1.2 km so every point still resolves to its own ward centroid rather
 * than drifting into the neighbouring ward.
 */
const CATEGORIES = ['pothole', 'streetlight', 'garbage', 'water', 'drainage', 'other'];

const DESCRIPTIONS = {
    pothole: [
        'Large pothole in the middle of the road, cars are swerving into oncoming traffic to avoid it.',
        'The road has broken up badly after the rain. Two-wheelers have come off here twice this week.',
        'Deep crater near the bus stop. It fills with water and you cannot tell how deep it is.',
        'Surface has collapsed where they dug for the cable last month and never repaired it properly.',
        'Pothole right at the junction. Autos slow to a crawl and it backs up the whole road.',
    ],
    streetlight: [
        'Street light has been out for three weeks. The whole stretch is pitch dark after 7pm.',
        'Two lamps on this road are dead. Women walking back from the metro have complained.',
        'Light flickers all night and then goes out. Been like this since the storm.',
        'No lighting at all near the park gate. It feels unsafe to walk here in the evening.',
    ],
    garbage: [
        'Garbage has not been collected for over a week. The pile is spilling onto the footpath.',
        'Dumping happening at the corner every night. Smell is unbearable and there are stray dogs.',
        'Bin overflowing since Monday. Crows have scattered waste across the whole street.',
        'Construction debris dumped on the roadside and left there. Blocking half the lane.',
    ],
    water: [
        'Water pipe has been leaking for days. Clean water running straight into the drain.',
        'No water supply in this area for four days now. Tankers are not reaching us.',
        'Burst pipeline near the junction, the road is flooded and the water is muddy.',
        'Very low pressure since last week. Cannot fill even one pot in the morning.',
    ],
    drainage: [
        'Drain is completely blocked. Sewage backing up onto the road outside the houses.',
        'Waterlogging every time it rains. Takes two days to clear and the whole lane is unusable.',
        'Storm drain cover is missing. Someone is going to fall into it at night.',
        'Open drain overflowing near the school gate. Children walk past it every day.',
    ],
    other: [
        'Tree branch broken and hanging over the power line after the storm.',
        'Footpath slab is broken and lifted. An elderly resident tripped on it yesterday.',
        'Stray cattle blocking the main road every morning, causing traffic backups.',
        'Illegal hoarding fixed to the electric pole, leaning badly and looks unsafe.',
    ],
};

/**
 * Shape of each ward's incidents: how many reports, and how many days back the
 * incident opened. Deliberately varied so the queue shows the full priority
 * ramp rather than a flat wall of one colour.
 *
 * [reportCount, ageDays]
 */
const SHAPES = [
    [14, 21], // heavy + old        -> critical
    [9, 12],  // busy + recent      -> high
    [6, 8],   // moderate           -> medium/high
    [4, 30],  // small but stale    -> medium
    [3, 4],   // small + fresh      -> medium/low
    [1, 2],   // lone report        -> low, "awaiting corroboration"
];

/* ------------------------------------------------------------------ util */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pick = (arr, i) => arr[i % arr.length];

/** ~0.009 degrees is roughly 1 km. Directions differ per hotspot. */
function hotspotOffset(index) {
    const angle = (index / CATEGORIES.length) * Math.PI * 2;
    const radiusDeg = 0.009;
    return { dLat: Math.sin(angle) * radiusDeg, dLon: Math.cos(angle) * radiusDeg };
}

/** Tight jitter so reports at one hotspot stay well inside the 1.5 km gate. */
function jitter() {
    return (Math.random() - 0.5) * 0.0016;
}

async function api(path, options = {}) {
    const response = await fetch(`${API}${path}`, {
        ...options,
        // Merged AFTER the spread: options carries its own headers, and
        // spreading it last would replace this object wholesale rather than
        // merge into it — silently dropping Content-Type.
        headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
    });
    const text = await response.text();
    if (!response.ok) {
        throw new Error(`${options.method ?? 'GET'} ${path} -> ${response.status}: ${text}`);
    }
    return text ? JSON.parse(text) : null;
}

/* ------------------------------------------------------------------ main */

async function main() {
    const wards = await api('/wards');
    console.log(`Seeding across ${wards.length} wards\n`);

    // One entry per hotspot: the complaints it produced, and how old it should
    // look. Which incident(s) they land in is not known until matching settles.
    const hotspots = [];
    let created = 0;

    for (const ward of wards) {
        for (let c = 0; c < CATEGORIES.length; c++) {
            const category = CATEGORIES[c];
            const [reportCount, ageDays] = SHAPES[c];
            const { dLat, dLon } = hotspotOffset(c);
            const lat = ward.lat + dLat;
            const long = ward.long + dLon;

            const complaintIds = [];
            for (let r = 0; r < reportCount; r++) {
                const complaint = await api('/complaints', {
                    method: 'POST',
                    body: JSON.stringify({
                        description: pick(DESCRIPTIONS[category], r),
                        category,
                        // wardId omitted on purpose: this exercises the real
                        // coordinate-to-ward derivation rather than bypassing it.
                        lat: lat + jitter(),
                        long: long + jitter(),
                    }),
                });
                created++;
                complaintIds.push(Number(complaint.id));
            }

            hotspots.push({ label: `${ward.name.padEnd(9)} ${category.padEnd(12)}`, ageDays, complaintIds });
            console.log(`  ${hotspots.at(-1).label} ${String(reportCount).padStart(2)} reports submitted`);
        }
    }

    const allIds = hotspots.flatMap((h) => h.complaintIds);
    console.log(`\n${created} complaints created (all matching_status=pending).`);

    /* ----------------------------------------------- wait for matching */

    const db = new Client({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });
    await db.connect();

    await settleMatching(db, allIds);

    /* ----------------------------------------------- resolve incidents */

    // A hotspot can produce more than one incident: the semantic matcher splits
    // it when two reports about the same problem describe it differently enough
    // to fall below the threshold. Backdate every incident it produced.
    const incidentAges = new Map(); // incidentId -> ageDays
    let fragmented = 0;

    for (const hotspot of hotspots) {
        const { rows } = await db.query(
            `SELECT DISTINCT incident_id FROM complaints
             WHERE id = ANY($1::bigint[]) AND incident_id IS NOT NULL`,
            [hotspot.complaintIds]
        );
        const ids = rows.map((r) => Number(r.incident_id));
        for (const id of ids) incidentAges.set(id, hotspot.ageDays);
        if (ids.length > 1) fragmented++;
        console.log(
            `  ${hotspot.label} -> ${ids.length === 1 ? `incident ${ids[0]}` : `${ids.length} incidents ${ids.join(', ')}`}`
        );
    }

    console.log(`\n${incidentAges.size} incidents formed from ${hotspots.length} hotspots.`);
    if (fragmented > 0) {
        console.log(
            `\n${fragmented} hotspot(s) split across multiple incidents. Usually this is the\n` +
            `concurrency race, not the threshold: this script submits far faster than the\n` +
            `matcher drains, so several reports about one problem get embedded in parallel,\n` +
            `each finds no committed sibling yet, and each starts its own incident.\n` +
            `A reconcile pass consolidates them — restart with matching enabled and wait for\n` +
            `MatchReconciliationJob, or re-run those complaints with ?force=true.\n\n` +
            `To tell the two causes apart, check incident_match_log:\n` +
            `  rows with candidate_count = 0        -> the race (no sibling was visible yet)\n` +
            `  rows with top_similarity < threshold -> genuinely dissimilar wording`
        );
    }

    /* --------------------------------------------------- backdate + rescore */

    console.log('\nBackdating so the age term of the priority formula has something to bite on…');

    for (const [incidentId, ageDays] of incidentAges) {
        // The incident opened ageDays ago; its reports are spread from then
        // until now, so recent arrivals still register as growth.
        await db.query(
            `UPDATE incidents SET created_at = NOW() - ($2 || ' days')::interval WHERE id = $1`,
            [incidentId, ageDays]
        );
        await db.query(
            `UPDATE complaints
             SET created_at = NOW() - (random() * $2 || ' days')::interval
             WHERE incident_id = $1`,
            [incidentId, ageDays]
        );
    }
    await db.end();

    await rescoreAll();

    console.log('');
    console.log('Done.');
}

/**
 * Blocks until every complaint this run created has left PENDING/PROCESSING.
 *
 * Scoped to our own ids on purpose — a pre-existing backlog from an earlier
 * failed run must not make this hang forever. On timeout it warns and carries
 * on: whatever did get grouped is still worth backdating, and Spring's
 * MatchReconciliationJob will pick up the stragglers on its own schedule.
 */
async function settleMatching(db, complaintIds) {
    const deadline = Date.now() + SETTLE_TIMEOUT_MS;
    let remaining = complaintIds.length;

    process.stdout.write('Waiting for the matching pipeline to settle');
    while (Date.now() < deadline) {
        const { rows } = await db.query(
            `SELECT count(*)::int AS pending FROM complaints
             WHERE id = ANY($1::bigint[]) AND matching_status IN ('PENDING', 'PROCESSING')`,
            [complaintIds]
        );
        remaining = rows[0].pending;
        if (remaining === 0) {
            console.log(' done.\n');
            return;
        }
        process.stdout.write('.');
        await sleep(1000);
    }

    console.log('');
    console.warn(
        `\nWARNING: ${remaining} complaint(s) still unmatched after ${SETTLE_TIMEOUT_MS / 1000}s.\n` +
        `Is backend-node running with EMBEDDING_API_KEY set? Carrying on anyway —\n` +
        `MatchReconciliationJob will catch them up.\n`
    );
}

/**
 * Recompute every incident's score and reasons through PriorityService.
 *
 * Goes through POST /internal/incidents/{id}/recompute, NOT /incidents/attach.
 * attach records a matching decision — it stamps matching_status and appends an
 * incident_match_log row — so replaying it here would invent "semantic match"
 * events that never happened and corrupt the Phase 8 evaluation dataset.
 * recompute only re-derives count, centroid, address, score and reasons.
 *
 * Useful beyond seeding: the age term of the formula is time-dependent, so a
 * stored score drifts even when nothing is written. Running this periodically
 * is what a scheduled refresh would otherwise do.
 */
async function rescoreAll() {
    const db = new Client({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });
    await db.connect();
    const { rows } = await db.query(`SELECT id FROM incidents ORDER BY id`);
    await db.end();

    console.log(`Recomputing priority for ${rows.length} incidents through the real service…`);
    for (const row of rows) {
        await api(`/internal/incidents/${row.id}/recompute`, {
            method: 'POST',
            headers: { 'X-Internal-Token': INTERNAL_TOKEN },
        });
        await sleep(20);
    }
}

// --rescore skips creation and only re-derives scores against current ages, so
// it can be re-run without multiplying the seed data.
const task = process.argv.includes('--rescore') ? rescoreAll : main;

task().catch((error) => {
    console.error('');
    console.error('Failed:', error.message);
    process.exit(1);
});
