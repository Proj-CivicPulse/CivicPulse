/**
 * Adversarial linkage tests — ISSUE-14.
 *
 * The other matching tests stub `pool.query`, which is right for the threshold
 * logic but cannot say anything about CHAINING, because single-linkage is not
 * implemented in TypeScript at all. It is this, in CANDIDATES_SQL:
 *
 *     MAX(1 - (c.embedding <=> $1::vector))
 *
 * The MAX is the linkage rule. Testing it with a stubbed pool would be testing
 * the stub. So these run against a real Postgres with pgvector, inside a
 * transaction that is always rolled back.
 *
 * They SKIP — they do not fail — when no database is reachable, so `npm test`
 * still works on a laptop with nothing running. CI with TEST_DATABASE_URL set
 * gets the real coverage.
 *
 * WHAT THESE TESTS ASSERT is that the documented behaviour is the actual
 * behaviour, including where it is uncomfortable: transitive chaining is real,
 * reachable with three ordinary complaints, and produces a group whose members
 * are NOT all similar to each other. That is a property to decide about with
 * evidence (docs/matching-contract.md, open question 2) — not a bug to
 * "fix" by swapping the linkage rule on instinct.
 */

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import pg from 'pg';

const DIM = 1536;
const CONNECTION = process.env.TEST_DATABASE_URL ?? process.env.DATABASE_URL;

/** The SQL under test, verbatim from matching.service.ts. */
const CANDIDATES_SQL = `
    SELECT i.id::text                                                AS incident_id,
           MAX(1 - (c.embedding <=> $1::vector))                     AS top_similarity,
           (ARRAY_AGG(c.id::text ORDER BY c.embedding <=> $1::vector))[1] AS top_sibling_id,
           COUNT(*)::int                                             AS member_count
    FROM incidents i
    JOIN complaints c ON c.incident_id = i.id
    WHERE i.ward_id = $2
      AND i.category = $3
      AND i.status IN ('OPEN', 'IN_PROGRESS')
      AND c.embedding IS NOT NULL
      AND c.id <> $4
    GROUP BY i.id
    ORDER BY top_similarity DESC
    LIMIT 20
`;

/**
 * A unit vector at a chosen angle in the plane spanned by dimensions 0 and 1,
 * zero elsewhere. Cosine similarity between two of these is cos(a - b), which
 * makes every similarity in these tests exact and stated rather than emergent.
 */
function unitVectorAtAngle(radians: number): number[] {
    const v = new Array(DIM).fill(0);
    v[0] = Math.cos(radians);
    v[1] = Math.sin(radians);
    return v;
}

const literal = (v: number[]) => `[${v.join(',')}]`;

/**
 * The first row of a result that must have one.
 *
 * Under `noUncheckedIndexedAccess`, `rows[0]` is `T | undefined` — correctly, as
 * a query CAN return nothing. Suppressing that with `!` would turn a query that
 * silently matched no rows into a confusing TypeError several lines later, so
 * this asserts instead and fails where the mistake actually is.
 */
function first<T>(result: { rows: T[] }, what: string): T {
    const row = result.rows[0];
    assert.ok(row, `expected at least one row for ${what}`);
    return row;
}

/** The shape CANDIDATES_SQL returns. Numerics come back as strings from pg. */
interface CandidateRow {
    incident_id: string;
    top_similarity: string;
    top_sibling_id: string;
    member_count: number;
}

let pool: pg.Pool | null = null;
let client: pg.PoolClient | null = null;
let reachable = false;

before(async () => {
    if (!CONNECTION || CONNECTION.includes('localhost:5432/civicpulse_test')) return;
    try {
        pool = new pg.Pool({ connectionString: CONNECTION, connectionTimeoutMillis: 20000 });
        client = await pool.connect();
        await client.query('SELECT 1');
        reachable = true;
    } catch {
        reachable = false;
    }
});

after(async () => {
    client?.release();
    await pool?.end();
});

/**
 * Runs a scenario inside a transaction that is ALWAYS rolled back, so these
 * tests can point at the development database without leaving anything behind.
 */
async function inRollback<T>(fn: (c: pg.PoolClient) => Promise<T>): Promise<T> {
    const c = client!;
    await c.query('BEGIN');
    try {
        return await fn(c);
    } finally {
        await c.query('ROLLBACK');
    }
}

/** Inserts a complaint carrying a known embedding, attached to an incident. */
async function seedComplaint(
    c: pg.PoolClient,
    opts: { wardId: string; incidentId: string | null; angle: number },
): Promise<string> {
    const row = await c.query<{ id: string }>(
        `INSERT INTO complaints
           (ward_id, incident_id, description, category, latitude, longitude, status,
            reference_no, matching_status, embedding, created_at, updated_at)
         VALUES ($1, $2, 'linkage probe', 'pothole', 12.9716, 77.5946, 'OPEN',
                 'TEST-' || substr(md5(random()::text), 1, 20), 'MATCHED', $3::vector, NOW(), NOW())
         RETURNING id::text`,
        [opts.wardId, opts.incidentId, literal(unitVectorAtAngle(opts.angle))],
    );
    return first(row, 'inserted complaint').id;
}

async function seedIncident(c: pg.PoolClient, wardId: string): Promise<string> {
    const row = await c.query<{ id: string }>(
        `INSERT INTO incidents (ward_id, category, status, complaint_count, priority_score,
                                priority_reasons, created_at, updated_at)
         VALUES ($1, 'pothole', 'OPEN', 0, 0, '[]'::jsonb, NOW(), NOW())
         RETURNING id::text`,
        [wardId],
    );
    return first(row, 'inserted incident').id;
}

async function anyWardId(c: pg.PoolClient): Promise<string> {
    const row = await c.query<{ id: string }>(
        `SELECT id::text FROM wards WHERE active ORDER BY id LIMIT 1`);
    return first(row, 'an active ward').id;
}

describe('single-linkage chaining (real pgvector)', () => {
    it('CHAINS: C joins a group it only half-resembles, via B', async (t) => {
        if (!reachable) return t.skip('no database reachable — set TEST_DATABASE_URL');

        await inRollback(async (c) => {
            const wardId = await anyWardId(c);
            const incidentId = await seedIncident(c, wardId);

            // A and B are 40 degrees apart: cos(40 deg) = 0.766, over the 0.75
            // threshold, so B legitimately joined A's incident.
            const A = 0;
            const B = (40 * Math.PI) / 180;
            // C is another 40 degrees beyond B. C-to-B is 0.766 (a match), but
            // C-to-A is cos(80 deg) = 0.174 — nothing like a match.
            const C = (80 * Math.PI) / 180;

            await seedComplaint(c, { wardId, incidentId, angle: A });
            const bId = await seedComplaint(c, { wardId, incidentId, angle: B });
            const cId = await seedComplaint(c, { wardId, incidentId: null, angle: C });

            const result = await c.query<CandidateRow>(CANDIDATES_SQL, [
                literal(unitVectorAtAngle(C)), wardId, 'pothole', cId,
            ]);

            const candidate = result.rows.find((r) => r.incident_id === incidentId);
            assert.ok(candidate, 'the incident should be a candidate');

            // The incident scores by its BEST member, which is B.
            assert.ok(
                Number(candidate.top_similarity) > 0.75,
                `expected a match via B, got ${candidate.top_similarity}`,
            );
            assert.equal(candidate.top_sibling_id, bId,
                'the match should be attributed to B, the member C actually resembles');

            // And this is the uncomfortable half: C is now grouped with A, which
            // it does not resemble at all. The group's diameter has grown well
            // past the threshold that admitted each member individually.
            const aId = first(await c.query<{ id: string }>(
                `SELECT id::text FROM complaints WHERE incident_id = $1 ORDER BY id LIMIT 1`,
                [incidentId]), 'complaint A').id;
            const toA = first(await c.query<{ sim: string }>(
                `SELECT 1 - (embedding <=> $1::vector) AS sim FROM complaints WHERE id = $2`,
                [literal(unitVectorAtAngle(C)), aId]), 'similarity of C to A');
            assert.ok(
                Number(toA.sim) < 0.5,
                `C should be dissimilar to A — that is the chaining cost (got ${toA.sim})`,
            );
        });
    });

    it('does NOT chain when no single member clears the threshold', async (t) => {
        if (!reachable) return t.skip('no database reachable — set TEST_DATABASE_URL');

        await inRollback(async (c) => {
            const wardId = await anyWardId(c);
            const incidentId = await seedIncident(c, wardId);

            // Both members sit 80 degrees from the newcomer: 0.174 each. A
            // chain needs a stepping stone, and there is none.
            await seedComplaint(c, { wardId, incidentId, angle: (80 * Math.PI) / 180 });
            await seedComplaint(c, { wardId, incidentId, angle: (85 * Math.PI) / 180 });
            const newId = await seedComplaint(c, { wardId, incidentId: null, angle: 0 });

            const result = await c.query<CandidateRow>(CANDIDATES_SQL, [
                literal(unitVectorAtAngle(0)), wardId, 'pothole', newId,
            ]);

            const candidate = result.rows.find((r) => r.incident_id === incidentId);
            assert.ok(candidate, 'it is still reported as a candidate, below threshold');
            assert.ok(
                Number(candidate.top_similarity) < 0.75,
                `expected no match, got ${candidate.top_similarity}`,
            );
        });
    });

    it('scores by the BEST member, not the average — the definition of single-linkage', async (t) => {
        if (!reachable) return t.skip('no database reachable — set TEST_DATABASE_URL');

        await inRollback(async (c) => {
            const wardId = await anyWardId(c);
            const incidentId = await seedIncident(c, wardId);

            // One near-identical member among nine distant ones. Average
            // linkage would reject this incident outright; single-linkage takes
            // it on the strength of the one.
            const twinId = await seedComplaint(c, { wardId, incidentId, angle: 0.01 });
            for (let i = 0; i < 9; i++) {
                await seedComplaint(c, { wardId, incidentId, angle: (85 * Math.PI) / 180 });
            }
            const newId = await seedComplaint(c, { wardId, incidentId: null, angle: 0 });

            const result = await c.query<CandidateRow>(CANDIDATES_SQL, [
                literal(unitVectorAtAngle(0)), wardId, 'pothole', newId,
            ]);
            const candidate = result.rows.find((r) => r.incident_id === incidentId);
            assert.ok(candidate, 'the incident should be a candidate');

            assert.ok(Number(candidate.top_similarity) > 0.99,
                'the single near-identical member must set the score');
            assert.equal(candidate.top_sibling_id, twinId,
                'and must be named, so an officer can click into it and check');
            assert.equal(candidate.member_count, 10);
        });
    });

    it('never returns a candidate from another ward or another category', async (t) => {
        if (!reachable) return t.skip('no database reachable — set TEST_DATABASE_URL');

        await inRollback(async (c) => {
            const wards = await c.query<{ id: string }>(
                `SELECT id::text FROM wards WHERE active ORDER BY id LIMIT 2`);
            const wardA = wards.rows[0]?.id;
            const wardB = wards.rows[1]?.id;
            assert.ok(wardA && wardB, 'this test needs at least two active wards');

            // An identical complaint sitting in a different ward.
            const otherWardIncident = await seedIncident(c, wardB);
            await seedComplaint(c, { wardId: wardB, incidentId: otherWardIncident, angle: 0 });

            // And an identical one in this ward under a different category.
            const wrongCategoryId = first(await c.query<{ id: string }>(
                `INSERT INTO incidents (ward_id, category, status, complaint_count, priority_score,
                                        priority_reasons, created_at, updated_at)
                 VALUES ($1, 'garbage', 'OPEN', 0, 0, '[]'::jsonb, NOW(), NOW())
                 RETURNING id::text`, [wardA]), 'wrong-category incident').id;
            await c.query(
                `INSERT INTO complaints (ward_id, incident_id, description, category, latitude,
                                         longitude, status, reference_no, matching_status,
                                         embedding, created_at, updated_at)
                 VALUES ($1, $2, 'linkage probe', 'garbage', 12.9716, 77.5946, 'OPEN',
                         'TEST-' || substr(md5(random()::text), 1, 20), 'MATCHED', $3::vector, NOW(), NOW())`,
                [wardA, wrongCategoryId, literal(unitVectorAtAngle(0))]);

            const newId = await seedComplaint(c, { wardId: wardA, incidentId: null, angle: 0 });

            const result = await c.query<CandidateRow>(CANDIDATES_SQL, [
                literal(unitVectorAtAngle(0)), wardA, 'pothole', newId,
            ]);

            const ids = result.rows.map((r) => r.incident_id);
            assert.ok(!ids.includes(otherWardIncident),
                'a perfect semantic match in another ward must not be a candidate');
            assert.ok(!ids.includes(wrongCategoryId),
                'a perfect semantic match in another category must not be a candidate');
        });
    });

    it('never matches a resolved or closed incident', async (t) => {
        if (!reachable) return t.skip('no database reachable — set TEST_DATABASE_URL');

        await inRollback(async (c) => {
            const wardId = await anyWardId(c);
            const incidentId = await seedIncident(c, wardId);
            await seedComplaint(c, { wardId, incidentId, angle: 0 });
            await c.query(`UPDATE incidents SET status = 'RESOLVED' WHERE id = $1`, [incidentId]);

            const newId = await seedComplaint(c, { wardId, incidentId: null, angle: 0 });
            const result = await c.query<CandidateRow>(CANDIDATES_SQL, [
                literal(unitVectorAtAngle(0)), wardId, 'pothole', newId,
            ]);

            assert.ok(!result.rows.some((r) => r.incident_id === incidentId),
                'a resolved incident must not attract new reports');
        });
    });
});
