import assert from 'node:assert/strict';
import { afterEach, describe, it, mock } from 'node:test';
import { pool } from '../db/pool.ts';
import { matchOrCreate } from './matching.service.ts';
import type { SpringComplaint } from './spring.client.ts';

const complaint: SpringComplaint = {
    id: '10',
    wardId: '3',
    incidentId: null,
    title: null,
    description: 'pothole on the main road',
    category: 'pothole',
    matchingStatus: 'processing',
};

const vector = [1, 0, 0, 0];

function stubCandidates(rows: unknown[]): void {
    mock.method(pool, 'query', async () => ({ rows }));
}

describe('matchOrCreate', () => {
    afterEach(() => mock.restoreAll());

    it('joins the top candidate when its similarity clears the threshold', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.88, top_sibling_id: '4821', member_count: 2 },
            { incident_id: '56', top_similarity: 0.5, top_sibling_id: '9', member_count: 1 },
        ]);

        const decision = await matchOrCreate(complaint, vector);

        assert.equal(decision.incidentId, '55');
        assert.equal(decision.topSimilarity, 0.88);
        assert.equal(decision.topSiblingComplaintId, '4821');
        assert.equal(decision.candidateCount, 2);
        assert.equal(decision.threshold, 0.75);
    });

    it('creates a new incident when the best score is below the threshold, but still reports it', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.71, top_sibling_id: '4821', member_count: 3 },
        ]);

        const decision = await matchOrCreate(complaint, vector);

        assert.equal(decision.incidentId, null);
        assert.equal(decision.topSimilarity, 0.71);
        assert.equal(decision.topSiblingComplaintId, '4821');
        assert.equal(decision.candidateCount, 1);
    });

    it('creates a new incident with null scores when there are no candidates', async () => {
        stubCandidates([]);

        const decision = await matchOrCreate(complaint, vector);

        assert.equal(decision.incidentId, null);
        assert.equal(decision.topSimilarity, null);
        assert.equal(decision.topSiblingComplaintId, null);
        assert.equal(decision.candidateCount, 0);
    });

    it('refuses a candidate whose most similar member is too far away, and says why', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.92, top_sibling_id: '4821', top_sibling_distance_m: 930, member_count: 2 },
        ]);

        const decision = await matchOrCreate(complaint, vector);

        // The same fault 930 m away is a different job: a new incident, but
        // the log still records the score similarity alone would have used.
        assert.equal(decision.incidentId, null);
        assert.equal(decision.topSimilarity, 0.92);
        assert.equal(decision.topSiblingComplaintId, '4821');
        assert.equal(decision.refusedByDistance, 1);
    });

    it('falls through to the next candidate that is both similar and near', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.92, top_sibling_id: '1', top_sibling_distance_m: 2100, member_count: 2 },
            { incident_id: '56', top_similarity: 0.81, top_sibling_id: '2', top_sibling_distance_m: 120, member_count: 3 },
            { incident_id: '57', top_similarity: 0.6, top_sibling_id: '3', top_sibling_distance_m: 10, member_count: 1 },
        ]);

        const decision = await matchOrCreate(complaint, vector);

        assert.equal(decision.incidentId, '56');
        assert.equal(decision.topSimilarity, 0.81);
        assert.equal(decision.refusedByDistance, 1);
    });

    it('accepts at exactly the distance limit, and when coordinates are unknown', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.8, top_sibling_id: '1', top_sibling_distance_m: 500, member_count: 1 },
        ]);
        assert.equal((await matchOrCreate(complaint, vector)).incidentId, '55');

        mock.restoreAll();
        stubCandidates([
            { incident_id: '55', top_similarity: 0.8, top_sibling_id: '1', top_sibling_distance_m: null, member_count: 1 },
        ]);
        assert.equal((await matchOrCreate(complaint, vector)).incidentId, '55');
    });

    it('treats an exact-threshold score as a join', async () => {
        stubCandidates([
            { incident_id: '55', top_similarity: 0.75, top_sibling_id: '4821', member_count: 1 },
        ]);

        const decision = await matchOrCreate(complaint, vector);

        assert.equal(decision.incidentId, '55');
    });
});
