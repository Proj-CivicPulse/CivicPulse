import assert from 'node:assert/strict';
import { afterEach, describe, it, mock } from 'node:test';
import { pool } from '../db/pool.ts';
import { claim, release } from './claim.ts';

describe('claim', () => {
    afterEach(() => mock.restoreAll());

    it('reports claimed with the pre-image status when the CAS updates a row', async () => {
        const queryMock = mock.method(pool, 'query', async () => ({
            rows: [{ previous_status: 'DEGRADED' }],
        }));

        const result = await claim('10', { force: false });

        assert.deepEqual(result, { claimed: true, previousStatus: 'DEGRADED' });
        // force flag and the stale window are bound as parameters, never interpolated
        const params = queryMock.mock.calls[0]!.arguments[1] as unknown as unknown[];
        assert.deepEqual(params, ['10', false, 45]);
    });

    it('reports not claimed when the CAS matches no row', async () => {
        mock.method(pool, 'query', async () => ({ rows: [] }));

        const result = await claim('10', { force: true });

        assert.deepEqual(result, { claimed: false, previousStatus: null });
    });
});

describe('release', () => {
    afterEach(() => mock.restoreAll());

    it('restores the given status, scoped to rows still in PROCESSING', async () => {
        const queryMock = mock.method(pool, 'query', async () => ({ rows: [] }));

        await release('10', 'PENDING');

        const [sql, params] = queryMock.mock.calls[0]!.arguments as unknown as [string, unknown[]];
        assert.match(sql, /matching_status = 'PROCESSING'/);
        assert.deepEqual(params, ['10', 'PENDING']);
    });
});
