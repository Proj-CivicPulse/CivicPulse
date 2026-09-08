import assert from 'node:assert/strict';
import { afterEach, describe, it, mock } from 'node:test';
import { buildEmbeddingText, generateEmbedding } from './embedding.service.ts';
import { HttpError } from '../middleware/errorHandler.ts';

describe('buildEmbeddingText', () => {
    it('prefixes the category and folds in the title when present', () => {
        const text = buildEmbeddingText({
            category: 'pothole',
            title: 'Large pothole',
            description: 'deep hole near the junction',
        });
        assert.equal(text, '[pothole] Large pothole\ndeep hole near the junction');
    });

    it('omits the title line when there is no title', () => {
        const text = buildEmbeddingText({
            category: 'garbage',
            title: null,
            description: 'uncollected for a week',
        });
        assert.equal(text, '[garbage] uncollected for a week');
    });
});

describe('generateEmbedding', () => {
    afterEach(() => mock.restoreAll());

    const okResponse = (values: number[]): Response =>
        new Response(JSON.stringify({ embedding: { values } }), {
            status: 200,
            headers: { 'content-type': 'application/json' },
        });

    it('L2-normalises the returned vector (EMBEDDING_DIMENSIONS=4 in tests)', async () => {
        mock.method(globalThis, 'fetch', async () => okResponse([3, 0, 4, 0]));

        const vector = await generateEmbedding('some complaint text');

        assert.deepEqual(vector, [0.6, 0, 0.8, 0]);
    });

    it('sends the model, task type and output dimensionality', async () => {
        const fetchMock = mock.method(globalThis, 'fetch', async () => okResponse([1, 0, 0, 0]));

        await generateEmbedding('hello');

        const [url, init] = fetchMock.mock.calls[0]!.arguments as [string, RequestInit];
        assert.match(url, /gemini-embedding-001:embedContent\?key=/);
        const body = JSON.parse(init.body as string);
        assert.equal(body.taskType, 'SEMANTIC_SIMILARITY');
        assert.equal(body.outputDimensionality, 4);
        assert.equal(body.content.parts[0].text, 'hello');
    });

    it('maps a provider error to 502 EMBEDDING_UPSTREAM_ERROR', async () => {
        mock.method(globalThis, 'fetch', async () => new Response('quota exceeded', { status: 429 }));

        await assert.rejects(generateEmbedding('x'), (err: unknown) => {
            assert.ok(err instanceof HttpError);
            assert.equal(err.status, 502);
            assert.equal(err.code, 'EMBEDDING_UPSTREAM_ERROR');
            return true;
        });
    });

    it('rejects a vector of the wrong length', async () => {
        mock.method(globalThis, 'fetch', async () => okResponse([1, 2, 3]));

        await assert.rejects(generateEmbedding('x'), (err: unknown) => {
            assert.ok(err instanceof HttpError);
            assert.equal(err.code, 'EMBEDDING_UPSTREAM_ERROR');
            return true;
        });
    });
});
