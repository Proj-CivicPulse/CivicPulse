import assert from 'node:assert/strict';
import { request } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';
import { createApp } from '../app.ts';
import { pool } from '../db/pool.ts';

/**
 * The public per-IP budget must not apply to the service-to-service matching
 * trigger. It used to: a reconcile backlog runs 25 calls a minute, burned the
 * 100-request budget in four minutes, and the resulting 429s were read by
 * backend-spring as "Node is down" — opening its circuit breaker and dumping
 * every complaint onto the naive fallback. The service rate-limited itself
 * into an outage that got worse the harder it tried to recover.
 */
function post(base: string, path: string, headers: Record<string, string> = {}) {
    return new Promise<{ status: number }>((resolve, reject) => {
        const req = request(`${base}${path}`, { method: 'POST', headers }, (res) => {
            res.resume();
            res.on('end', () => resolve({ status: res.statusCode ?? 0 }));
        });
        req.on('error', reject);
        req.end();
    });
}

describe('generalLimiter exemptions', () => {
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

    it('never 429s the internal matching trigger, well past the 100-request budget', async () => {
        // Claim always refused, so each call is a cheap 200 {skipped:true}.
        mock.method(pool, 'query', async () => ({ rows: [] }));

        const statuses = new Set<number>();
        for (let i = 0; i < 120; i++) {
            const res = await post(base, '/complaints/1/process', {
                'x-internal-token': 'test-internal-token',
            });
            statuses.add(res.status);
        }

        assert.deepEqual([...statuses], [200], `expected only 200s, saw ${[...statuses]}`);
    });

    it('still rate-limits ordinary routes', async () => {
        const statuses = new Set<number>();
        for (let i = 0; i < 120; i++) {
            statuses.add((await post(base, '/copilot/query')).status);
        }

        assert.ok(statuses.has(429), `expected the public budget to bite, saw ${[...statuses]}`);
    });
});
