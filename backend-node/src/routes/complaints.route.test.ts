import assert from 'node:assert/strict';
import { request } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';
import { createApp } from '../app.ts';
import { pool } from '../db/pool.ts';

/** Minimal POST helper — node:http, so it never collides with a mocked global fetch. */
function post(
    base: string,
    path: string,
    headers: Record<string, string> = {},
): Promise<{ status: number; body: string }> {
    return new Promise((resolve, reject) => {
        const req = request(
            `${base}${path}`,
            { method: 'POST', headers: { 'content-type': 'application/json', ...headers } },
            (res) => {
                let body = '';
                res.on('data', (c) => (body += c));
                res.on('end', () => resolve({ status: res.statusCode ?? 0, body }));
            },
        );
        req.on('error', reject);
        req.end();
    });
}

describe('POST /complaints/:id/process', () => {
    let server: ReturnType<ReturnType<typeof createApp>['listen']>;
    let base: string;

    beforeEach(() => {
        server = createApp().listen(0);
        base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    afterEach(async () => {
        mock.restoreAll();
        await new Promise<void>((resolve) => server.close(() => resolve()));
    });

    it('401s without a valid internal token', async () => {
        const res = await post(base, '/complaints/10/process');
        assert.equal(res.status, 401);
        assert.equal(JSON.parse(res.body).error.code, 'UNAUTHORIZED');
    });

    it('returns { skipped: true } when the claim matches no row', async () => {
        mock.method(pool, 'query', async () => ({ rows: [] }));

        const res = await post(base, '/complaints/10/process', {
            'x-internal-token': 'test-internal-token',
        });

        assert.equal(res.status, 200);
        assert.deepEqual(JSON.parse(res.body), { complaintId: '10', skipped: true });
    });

    it('releases the claim when a downstream call fails, then surfaces the error', async () => {
        const calls: string[] = [];
        mock.method(pool, 'query', async (sql: string) => {
            calls.push(sql);
            if (/SET matching_status = 'PROCESSING', updated_at = now\(\)\s*\n\s*FROM prev/.test(sql)) {
                return { rows: [{ previous_status: 'PENDING' }] };
            }
            return { rows: [] }; // the release UPDATE
        });
        // backend-spring unreachable -> springClient.getComplaint throws
        mock.method(globalThis, 'fetch', async () => {
            throw new Error('ECONNREFUSED');
        });

        const res = await post(base, '/complaints/10/process', {
            'x-internal-token': 'test-internal-token',
        });

        assert.equal(res.status, 502);
        const released = calls.some((s) => /matching_status = \$2/.test(s) && /= 'PROCESSING'/.test(s));
        assert.ok(released, 'expected the claim to be released after the failure');
    });
});
