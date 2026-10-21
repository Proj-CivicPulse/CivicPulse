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
 * Local: needs the app running, INTERNAL_TOKEN in backend-spring/.env and
 * DATABASE_URL in backend-node/.env.
 *
 * DEPLOYED (Railway): run from the repo root after `railway link` and with an
 * SSH key registered (`railway ssh keys add`):
 *
 *   SEED_API=https://<spring-domain> SEED_DB=railway node backend-spring/scripts/seed-dev-data.mjs
 *
 * SEED_DB=railway sends the SQL steps through `railway ssh` into the
 * civicpulse-db service, so the database needs no public proxy and no password
 * here. The rescore step needs the deployed INTERNAL_TOKEN (SEED_INTERNAL_TOKEN);
 * without it the script marks the incidents stale instead, and Spring's
 * PriorityRefreshJob rescores them on its next 5-minute sweep.
 *
 * SEED_WARDS (default 6) picks that many wards spread across the ward list.
 * Since V13 there are 243 real wards; seeding all of them is ~9,000 complaints.
 */

import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const springDir = join(here, '..');
// pg lives in backend-node; this script is the only consumer in this service.
const require = createRequire(join(springDir, '..', 'backend-node', 'package.json'));
const { Client } = require('pg');

const API = process.env.SEED_API ?? 'http://localhost:8080';
const VIA_RAILWAY = process.env.SEED_DB === 'railway';
const WARD_COUNT = Number(process.env.SEED_WARDS ?? 6);

/**
 * How long to wait for asynchronous matching to finish before giving up.
 *
 * At the default ward count a seed is ~230 complaints, each costing one
 * embedding round trip through backend-node. Generous on purpose: exceeding it
 * is a warning, not a failure.
 */
const SETTLE_TIMEOUT_MS = Number(process.env.SEED_SETTLE_TIMEOUT_MS ?? 300_000);

function envValue(file, key) {
    if (!existsSync(file)) return null;
    const match = readFileSync(file, 'utf8').match(new RegExp(`^${key}=(.*)$`, 'm'));
    return match ? match[1].trim() : null;
}

// A deployed backend never shares the local .env's token, so it is not read
// from there when seeding Railway.
const INTERNAL_TOKEN =
    process.env.SEED_INTERNAL_TOKEN ?? (VIA_RAILWAY ? null : envValue(join(springDir, '.env'), 'INTERNAL_TOKEN'));
const DATABASE_URL =
    process.env.SEED_DATABASE_URL ?? envValue(join(springDir, '..', 'backend-node', '.env'), 'DATABASE_URL');

if (!VIA_RAILWAY && !INTERNAL_TOKEN) throw new Error('INTERNAL_TOKEN missing from backend-spring/.env');
if (!VIA_RAILWAY && !DATABASE_URL) throw new Error('DATABASE_URL missing from backend-node/.env');

/**
 * SQL access: direct (pg), or through `railway ssh` + psql inside the database
 * container. Every value the queries interpolate is an integer this script
 * produced, so they carry no bind parameters and both paths run identical
 * text. Rows come back as arrays.
 */
async function openDb() {
    if (VIA_RAILWAY) {
        return { query: async (sql) => railwaySql(sql), end: async () => {} };
    }
    // SSL only where the server demands it (Neon); a local container has none.
    const ssl = /sslmode=require/.test(DATABASE_URL) ? { rejectUnauthorized: false } : false;
    const client = new Client({ connectionString: DATABASE_URL, ssl });
    await client.connect();
    return {
        query: async (sql) => {
            const result = await client.query({ text: sql, rowMode: 'array' });
            return (Array.isArray(result) ? result.at(-1) : result).rows;
        },
        end: () => client.end(),
    };
}

function railwaySql(sql) {
    // base64 so the SQL crosses both shells (local and remote) untouched.
    const encoded = Buffer.from(sql, 'utf8').toString('base64');
    const remote = `echo ${encoded} | base64 -d | psql -U "\${POSTGRES_USER:-civicpulse}" -d "\${POSTGRES_DB:-civicpulse}" -At -v ON_ERROR_STOP=1`;
    const out = execSync(`railway ssh -s civicpulse-db -- '${remote}'`, {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'inherit'],
        maxBuffer: 16 * 1024 * 1024,
        // A POSIX shell on every platform, so the quoting above means one thing.
        shell: process.platform === 'win32' ? 'bash' : '/bin/sh',
    });
    // psql -At prints one row per line, columns joined by '|'. Anything else
    // (a banner, an UPDATE tag) is not a data row.
    return out
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => /^-?\d+(\|-?\d+)*$/.test(line))
        .map((line) => line.split('|').map(Number));
}

/** Integers only — the one thing these queries ever interpolate. */
const ints = (values) => values.map((v) => Number.parseInt(v, 10)).filter(Number.isFinite).join(',');

/* ------------------------------------------------------------------ data */

/**
 * One hotspot becomes one incident. Categories are unique per ward so the
 * ward+category grouper cannot accidentally merge two of them, and offsets stay
 * under ~0.5 km of the ward's centre: since V13 wards are real BBMP polygons,
 * often only 1–2 km across, so a wider ring drifts into the neighbouring ward.
 */
const CATEGORIES = ['pothole', 'streetlight', 'garbage', 'water', 'drainage', 'other'];

/**
 * Alternative SPELLINGS the submit form would never send, rotated through
 * alongside the canonical code.
 *
 * This is the fixture for the category registry (migration V11). Before it
 * existed, a hotspot seeded as 'Garbage' and 'solid waste' became two incidents
 * that could never merge, because every consumer compares category with exact
 * equality. Now every one of these resolves to the same canonical code, so the
 * hotspot stays one candidate pool — and the seed proves it rather than
 * asserting it, because a regression here shows up as a hotspot that splits.
 *
 * Every value listed is a real alias row. An unlisted spelling is rejected with
 * a 400 by design, which is also worth seeing once.
 */
const CATEGORY_SPELLINGS = {
    pothole: ['pothole', 'Road Damage', 'road-damage', 'POTHOLES'],
    streetlight: ['streetlight', 'Street Light', 'street_light', 'Street Lighting'],
    garbage: ['garbage', 'Solid Waste', 'Uncollected Garbage', 'trash'],
    water: ['water', 'Water Supply', 'water-leak', 'burst pipe'],
    drainage: ['drainage', 'Blocked Drain', 'water logging', 'SEWAGE'],
    other: ['other', 'Miscellaneous', 'general', 'Something Else'],
};

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

/** ~0.0045 degrees is roughly 500 m. Directions differ per hotspot. */
function hotspotOffset(index) {
    const angle = (index / CATEGORIES.length) * Math.PI * 2;
    const radiusDeg = 0.0045;
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
    // Evenly spaced through the list, so the seed lands across the city map
    // instead of in one corner of it.
    const allWards = await api('/wards');
    const count = Math.min(WARD_COUNT, allWards.length);
    const wards = Array.from({ length: count }, (_, i) => allWards[Math.floor((i * allWards.length) / count)]);
    console.log(`Seeding ${wards.length} of ${allWards.length} wards: ${wards.map((w) => w.name).join(', ')}\n`);

    // One entry per hotspot: where it is, its reports, and how old it should
    // look. Which incident(s) they land in is not known until matching settles.
    const hotspots = [];
    for (const ward of wards) {
        for (let c = 0; c < CATEGORIES.length; c++) {
            const [reportCount, ageDays] = SHAPES[c];
            const { dLat, dLon } = hotspotOffset(c);
            hotspots.push({
                label: `${ward.name.padEnd(9)} ${CATEGORIES[c].padEnd(12)}`,
                category: CATEGORIES[c],
                lat: ward.lat + dLat,
                long: ward.long + dLon,
                reportCount,
                ageDays,
                complaintIds: [],
            });
        }
    }

    async function submit(hotspot, r) {
        const complaint = await api('/complaints', {
            method: 'POST',
            body: JSON.stringify({
                description: pick(DESCRIPTIONS[hotspot.category], r),
                // Deliberately NOT the canonical code every time: each report in
                // a hotspot uses a different accepted spelling, so the registry's
                // normalisation is on the path every run rather than only in
                // unit tests.
                category: pick(CATEGORY_SPELLINGS[hotspot.category], r),
                // wardId omitted on purpose: this exercises the real
                // coordinate-to-ward derivation rather than bypassing it.
                lat: hotspot.lat + jitter(),
                long: hotspot.long + jitter(),
            }),
        });
        hotspot.complaintIds.push(Number(complaint.id));
    }

    const db = await openDb();

    // Two waves. Matching is asynchronous, so a burst of reports about one
    // problem all embed in parallel, each finds no committed sibling yet, and
    // each starts its own incident — a hotspot of 14 used to land as two or
    // three. Seeding each hotspot's FIRST report and letting it settle gives
    // every later report an incident to join, which is also how real reports
    // arrive: one at a time.
    for (const hotspot of hotspots) await submit(hotspot, 0);
    console.log(`Wave 1: ${hotspots.length} first reports submitted, one per hotspot.`);
    await settleMatching(db, hotspots.map((h) => h.complaintIds[0]));

    for (const hotspot of hotspots) {
        for (let r = 1; r < hotspot.reportCount; r++) await submit(hotspot, r);
        console.log(`  ${hotspot.label} ${String(hotspot.reportCount).padStart(2)} reports submitted`);
    }

    const allIds = hotspots.flatMap((h) => h.complaintIds);
    console.log(`\n${allIds.length} complaints created.`);

    /* ----------------------------------------------- wait for matching */

    await settleMatching(db, allIds);

    /* ----------------------------------------------- resolve incidents */

    // A hotspot can produce more than one incident: the semantic matcher splits
    // it when two reports about the same problem describe it differently enough
    // to fall below the threshold. Backdate every incident it produced.
    const incidentAges = new Map(); // incidentId -> ageDays
    let fragmented = 0;

    // One round trip for every hotspot: each row is complaint id | incident id.
    const membership = new Map(
        (await db.query(
            `SELECT id, incident_id FROM complaints
             WHERE id IN (${ints(allIds)}) AND incident_id IS NOT NULL`
        )).map(([complaintId, incidentId]) => [Number(complaintId), Number(incidentId)])
    );

    for (const hotspot of hotspots) {
        const ids = [...new Set(hotspot.complaintIds.map((id) => membership.get(id)).filter(Boolean))];
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

    // The incident opened ageDays ago; its reports are spread from then until
    // now, so recent arrivals still register as growth. reported_at (V16) is
    // what every reader uses, so it moves with created_at, and the incident's
    // report span (V21) is re-derived from its members the way recompute
    // does it. One transaction, so a failure leaves nothing half-backdated.
    if (incidentAges.size > 0) {
        const ages = [...incidentAges].map(([id, days]) => `(${ints([id])}, ${ints([days])})`).join(', ');
        await db.query(`
            BEGIN;
            CREATE TEMP TABLE seed_ages (incident_id bigint, days int) ON COMMIT DROP;
            INSERT INTO seed_ages VALUES ${ages};
            UPDATE incidents i SET created_at = NOW() - make_interval(days => a.days)
              FROM seed_ages a WHERE i.id = a.incident_id;
            UPDATE complaints c SET created_at = NOW() - random() * make_interval(days => a.days)
              FROM seed_ages a WHERE c.incident_id = a.incident_id;
            UPDATE complaints c SET reported_at = c.created_at
              FROM seed_ages a WHERE c.incident_id = a.incident_id;
            UPDATE incidents i
               SET first_reported_at    = span.first_reported,
                   last_reported_at     = span.last_reported,
                   priority_computed_at = NULL
              FROM (SELECT incident_id, MIN(reported_at) AS first_reported, MAX(reported_at) AS last_reported
                      FROM complaints WHERE incident_id IN (SELECT incident_id FROM seed_ages)
                     GROUP BY incident_id) span
             WHERE i.id = span.incident_id;
            COMMIT;`);
    }
    await db.end();

    if (INTERNAL_TOKEN) {
        await rescoreAll();
    } else {
        console.log(
            'No SEED_INTERNAL_TOKEN: the incidents are marked stale instead, and the deployed\n' +
            "PriorityRefreshJob rescores them on its next sweep (within 5 minutes)."
        );
    }

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
        const rows = await db.query(
            `SELECT count(*)::int FROM complaints
             WHERE id IN (${ints(complaintIds)}) AND matching_status IN ('PENDING', 'PROCESSING')`
        );
        remaining = Number(rows[0][0]);
        if (remaining === 0) {
            console.log(' done.\n');
            return;
        }
        process.stdout.write('.');
        // Each Railway poll is an SSH round trip of its own; no need to hurry it.
        await sleep(VIA_RAILWAY ? 5000 : 1000);
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
    if (!INTERNAL_TOKEN) {
        throw new Error('--rescore needs SEED_INTERNAL_TOKEN (or INTERNAL_TOKEN in backend-spring/.env)');
    }
    const db = await openDb();
    const rows = await db.query(`SELECT id FROM incidents ORDER BY id`);
    await db.end();

    console.log(`Recomputing priority for ${rows.length} incidents through the real service…`);
    for (const [id] of rows) {
        await api(`/internal/incidents/${id}/recompute`, {
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
