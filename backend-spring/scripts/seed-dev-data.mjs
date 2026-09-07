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
 * The one thing the API cannot give us is AGE: everything it creates is new, so
 * the age term of the priority formula would be zero for every incident and the
 * whole queue would score the same. So the script backdates created_at in SQL,
 * then replays POST /internal/incidents/attach for each incident, which calls
 * IncidentAttachmentService.recompute() and re-derives the score and reasons
 * against the ages that now exist.
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

    const incidentAges = new Map(); // incidentId -> ageDays
    let created = 0;

    for (const ward of wards) {
        for (let c = 0; c < CATEGORIES.length; c++) {
            const category = CATEGORIES[c];
            const [reportCount, ageDays] = SHAPES[c];
            const { dLat, dLon } = hotspotOffset(c);
            const lat = ward.lat + dLat;
            const long = ward.long + dLon;

            let incidentId = null;
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
                incidentId = complaint.incidentId;
            }

            if (incidentId) incidentAges.set(incidentId, ageDays);
            console.log(
                `  ${ward.name.padEnd(9)} ${category.padEnd(12)} ` +
                `${String(reportCount).padStart(2)} reports  ->  incident ${incidentId}`
            );
        }
    }

    console.log(`\n${created} complaints created, ${incidentAges.size} incidents formed.`);

    /* --------------------------------------------------- backdate + rescore */

    console.log('\nBackdating so the age term of the priority formula has something to bite on…');
    const db = new Client({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });
    await db.connect();

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
 * Recompute every incident's score and reasons through PriorityService.
 *
 * Re-attaching a complaint that is already in its incident is idempotent —
 * complaintCount is recounted from the database rather than incremented — so
 * this is the one route to recompute() that does not require inventing an
 * endpoint just for it.
 *
 * Useful beyond seeding: the age term of the formula is time-dependent, so a
 * stored score drifts even when nothing is written. Running this periodically
 * is what a scheduled refresh would otherwise do.
 */
async function rescoreAll() {
    const db = new Client({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });
    await db.connect();
    // One member per incident is enough; attach recomputes over the whole set.
    const { rows } = await db.query(
        `SELECT incident_id, MIN(id) AS complaint_id
         FROM complaints WHERE incident_id IS NOT NULL
         GROUP BY incident_id ORDER BY incident_id`
    );
    await db.end();

    console.log(`Recomputing priority for ${rows.length} incidents through the real service…`);
    for (const row of rows) {
        await api('/internal/incidents/attach', {
            method: 'POST',
            headers: { 'X-Internal-Token': INTERNAL_TOKEN },
            body: JSON.stringify({
                complaintId: String(row.complaint_id),
                incidentId: String(row.incident_id),
            }),
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
