import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import Anthropic from '@anthropic-ai/sdk';
import { anthropicProvider, geminiProvider, ollamaProvider, parseOutput } from './copilotProviders.ts';
import { HttpError } from '../middleware/errorHandler.ts';

const good = { answerable: true, answer: 'Two incidents.', citations: ['metric:active_incidents'] };

/** A fetch stand-in that records the request and returns a canned response. */
function fakeFetch(status: number, body: unknown) {
    const calls: { url: string; init: RequestInit }[] = [];
    const impl = (async (url: string, init: RequestInit) => {
        calls.push({ url, init });
        return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
    }) as unknown as typeof fetch;
    return { impl, calls };
}

describe('parseOutput', () => {
    it('accepts exactly the agreed shape and nothing else', () => {
        assert.deepEqual(parseOutput(JSON.stringify(good)), good);
        assert.equal(parseOutput('not json'), null);
        assert.equal(parseOutput(JSON.stringify({ answer: 'missing fields' })), null);
        assert.equal(parseOutput(''), null);
    });
});

describe('ollama provider', () => {
    it('asks the local model for schema-constrained JSON at temperature 0', async () => {
        const { impl, calls } = fakeFetch(200, {
            model: 'qwen2.5:7b',
            message: { content: JSON.stringify(good) },
            done_reason: 'stop',
        });

        const result = await ollamaProvider(impl).answer('SYSTEM', 'USER');

        assert.deepEqual(result, { output: good, refused: false, truncated: false, model: 'qwen2.5:7b' });
        assert.equal(calls[0]!.url, 'http://localhost:11434/api/chat');
        const body = JSON.parse(String(calls[0]!.init.body));
        assert.equal(body.stream, false);
        assert.equal(body.options.temperature, 0);
        assert.equal(body.format.type, 'object');
        assert.deepEqual(body.format.required.sort(), ['answer', 'answerable', 'citations']);
        assert.deepEqual(body.messages.map((m: { role: string }) => m.role), ['system', 'user']);
    });

    it('says how to fix it when Ollama is down or the model is not pulled', async () => {
        const down = (async () => {
            throw new TypeError('fetch failed');
        }) as unknown as typeof fetch;
        await assert.rejects(ollamaProvider(down).answer('S', 'U'), (err: unknown) => {
            assert.ok(err instanceof HttpError);
            assert.equal(err.code, 'COPILOT_UNAVAILABLE');
            assert.match(err.message, /Ollama running/);
            return true;
        });
        await assert.rejects(ollamaProvider(fakeFetch(404, { error: 'model not found' }).impl).answer('S', 'U'), (err: unknown) => {
            assert.ok(err instanceof HttpError);
            assert.match(err.message, /ollama pull/);
            return true;
        });
    });

    it('flags a cut-off answer', async () => {
        const { impl } = fakeFetch(200, { message: { content: '{"answerable": tr' }, done_reason: 'length' });
        const result = await ollamaProvider(impl).answer('S', 'U');
        assert.equal(result.truncated, true);
        assert.equal(result.output, null);
    });
});

describe('gemini provider', () => {
    it('asks for JSON against the response schema and reads the first candidate', async () => {
        const { impl, calls } = fakeFetch(200, {
            candidates: [{ content: { parts: [{ text: JSON.stringify(good) }] }, finishReason: 'STOP' }],
            modelVersion: 'gemini-2.5-flash',
        });

        const result = await geminiProvider(impl).answer('SYSTEM', 'USER');

        assert.deepEqual(result, { output: good, refused: false, truncated: false, model: 'gemini-2.5-flash' });
        assert.match(calls[0]!.url, /models\/gemini-2\.5-flash:generateContent\?key=/);
        const body = JSON.parse(String(calls[0]!.init.body));
        assert.equal(body.generationConfig.responseMimeType, 'application/json');
        assert.equal(body.generationConfig.temperature, 0);
        assert.equal(body.systemInstruction.parts[0].text, 'SYSTEM');
    });

    it('maps a safety block to refused, and HTTP errors to the Copilot error codes', async () => {
        const blocked = fakeFetch(200, { promptFeedback: { blockReason: 'SAFETY' } }).impl;
        assert.equal((await geminiProvider(blocked).answer('S', 'U')).refused, true);

        for (const [status, code] of [[403, 'COPILOT_UNAVAILABLE'], [429, 'COPILOT_BUSY'], [500, 'COPILOT_UPSTREAM_ERROR']] as const) {
            await assert.rejects(geminiProvider(fakeFetch(status, {}).impl).answer('S', 'U'), { code });
        }
    });
});

describe('anthropic provider', () => {
    const client = (respond: () => unknown) =>
        ({ beta: { messages: { parse: async () => respond() } } }) as unknown as Anthropic;

    it('503s when no key is configured', async () => {
        await assert.rejects(anthropicProvider().answer('S', 'U'), { code: 'COPILOT_UNAVAILABLE' });
    });

    it('returns the parsed output, and reports refusal and truncation from stop_reason', async () => {
        const ok = await anthropicProvider(client(() => ({ model: 'claude-opus-5-5', stop_reason: 'end_turn', parsed_output: good }))).answer('S', 'U');
        assert.deepEqual(ok, { output: good, refused: false, truncated: false, model: 'claude-opus-5-5' });

        const refused = await anthropicProvider(client(() => ({ model: 'claude-opus-5-5', stop_reason: 'refusal', parsed_output: null }))).answer('S', 'U');
        assert.equal(refused.refused, true);

        const cut = await anthropicProvider(client(() => ({ model: 'claude-opus-5-5', stop_reason: 'max_tokens', parsed_output: null }))).answer('S', 'U');
        assert.equal(cut.truncated, true);
    });

    it('maps SDK errors: bad key → unavailable, rate limit → busy, outage → upstream', async () => {
        const headers = new Headers();
        const cases: [unknown, string][] = [
            [new Anthropic.AuthenticationError(401, undefined, 'bad key', headers), 'COPILOT_UNAVAILABLE'],
            [new Anthropic.RateLimitError(429, undefined, 'slow down', headers), 'COPILOT_BUSY'],
            [new Anthropic.InternalServerError(500, undefined, 'boom', headers), 'COPILOT_UPSTREAM_ERROR'],
            [new Error('socket hang up'), 'COPILOT_UPSTREAM_ERROR'],
        ];
        for (const [thrown, code] of cases) {
            const throwing = client(() => {
                throw thrown;
            });
            await assert.rejects(anthropicProvider(throwing).answer('S', 'U'), { code });
        }
    });
});
