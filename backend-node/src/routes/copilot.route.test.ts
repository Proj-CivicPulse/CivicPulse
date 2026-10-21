import assert from 'node:assert/strict';
import { request } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { createApp } from '../app.ts';
import { setCopilotProvider } from '../services/copilot.service.ts';

function post(base: string, path: string, body: unknown, headers: Record<string, string> = {}) {
    return new Promise<{ status: number; body: string }>((resolve, reject) => {
        const req = request(
            `${base}${path}`,
            { method: 'POST', headers: { 'content-type': 'application/json', ...headers } },
            (res) => {
                let text = '';
                res.on('data', (c) => (text += c));
                res.on('end', () => resolve({ status: res.statusCode ?? 0, body: text }));
            },
        );
        req.on('error', reject);
        req.end(JSON.stringify(body));
    });
}

const TOKEN = { 'x-internal-token': 'test-internal-token' };

describe('POST /internal/copilot/answer', () => {
    let server: ReturnType<ReturnType<typeof createApp>['listen']>;
    let base: string;

    beforeEach(() => {
        server = createApp().listen(0);
        base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    afterEach(async () => {
        setCopilotProvider(undefined);
        await new Promise<void>((resolve) => server.close(() => resolve()));
    });

    it('401s without the internal token — browsers cannot reach it', async () => {
        const res = await post(base, '/internal/copilot/answer', { question: 'q', packet: {} });
        assert.equal(res.status, 401);
    });

    it('400s on a missing question or a non-object packet', async () => {
        const blank = await post(base, '/internal/copilot/answer', { question: '  ', packet: {} }, TOKEN);
        assert.equal(blank.status, 400);
        const list = await post(base, '/internal/copilot/answer', { question: 'q', packet: [] }, TOKEN);
        assert.equal(list.status, 400);
    });

    it('503s COPILOT_UNAVAILABLE when no provider is configured', async () => {
        const res = await post(base, '/internal/copilot/answer', { question: 'q', packet: {} }, TOKEN);
        assert.equal(res.status, 503);
        assert.equal(JSON.parse(res.body).error.code, 'COPILOT_UNAVAILABLE');
    });

    it('returns the answer when the model responds', async () => {
        setCopilotProvider({
            name: 'fake',
            answer: async () => ({
                output: { answerable: true, answer: 'Two incidents.', citations: ['metric:active_incidents'] },
                refused: false,
                truncated: false,
                model: 'claude-opus-5-5',
            }),
        });

        const res = await post(base, '/internal/copilot/answer', { question: 'how many?', packet: { metrics: [] } }, TOKEN);

        assert.equal(res.status, 200);
        assert.deepEqual(JSON.parse(res.body), {
            answerable: true,
            answer: 'Two incidents.',
            citations: ['metric:active_incidents'],
            model: 'claude-opus-5-5',
            refused: false,
        });
    });

    it('no longer serves the old browser-facing POST /copilot/query', async () => {
        const res = await post(base, '/copilot/query', { officer_id: '7', query: 'q' });
        assert.equal(res.status, 404);
    });
});
