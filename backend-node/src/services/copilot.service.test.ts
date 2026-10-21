import assert from 'node:assert/strict';
import { afterEach, describe, it } from 'node:test';
import { answerQuestion, setCopilotProvider, SYSTEM_PROMPT, userText } from './copilot.service.ts';
import type { CopilotProvider, ProviderResult } from './copilotProviders.ts';
import { HttpError } from '../middleware/errorHandler.ts';

const packet = { scope: { ward: 'all wards' }, activeIncidents: { items: [{ id: 'incident:45' }] } };

function fake(result: Partial<ProviderResult>): CopilotProvider & { seen: string[][] } {
    const seen: string[][] = [];
    return {
        name: 'fake',
        seen,
        async answer(system, text) {
            seen.push([system, text]);
            return { output: null, refused: false, truncated: false, model: 'fake-model', ...result };
        },
    };
}

describe('copilot answerQuestion', () => {
    afterEach(() => setCopilotProvider(undefined));

    it('503s COPILOT_UNAVAILABLE when no provider is configured', async () => {
        // test.setup leaves COPILOT_PROVIDER unset, i.e. "none".
        await assert.rejects(answerQuestion('what is urgent?', packet), (err: unknown) => {
            assert.ok(err instanceof HttpError);
            assert.equal(err.status, 503);
            assert.equal(err.code, 'COPILOT_UNAVAILABLE');
            return true;
        });
    });

    it('gives the provider the fixed rules and the packet as data, then returns its answer', async () => {
        const provider = fake({
            output: { answerable: true, answer: 'Incident 45 is most urgent.', citations: ['incident:45'] },
        });
        setCopilotProvider(provider);

        const answer = await answerQuestion('what is urgent?', packet);

        assert.deepEqual(answer, {
            answerable: true,
            answer: 'Incident 45 is most urgent.',
            citations: ['incident:45'],
            model: 'fake-model',
            refused: false,
        });
        const [system, text] = provider.seen[0]!;
        assert.equal(system, SYSTEM_PROMPT);
        assert.equal(text, userText('what is urgent?', packet));
        assert.ok(text!.startsWith('<grounding>'));
        assert.ok(text!.endsWith('<question>\nwhat is urgent?\n</question>'));
    });

    it('reports a refusal as refused — not an error — and cites nothing', async () => {
        setCopilotProvider(fake({ refused: true }));
        const answer = await answerQuestion('q', packet);
        assert.equal(answer.refused, true);
        assert.equal(answer.answerable, false);
        assert.deepEqual(answer.citations, []);
    });

    it('treats a truncated or unreadable answer as an upstream failure', async () => {
        setCopilotProvider(fake({ truncated: true, output: { answerable: true, answer: 'x', citations: [] } }));
        await assert.rejects(answerQuestion('q', packet), { code: 'COPILOT_UPSTREAM_ERROR' });
        setCopilotProvider(fake({ output: null }));
        await assert.rejects(answerQuestion('q', packet), { code: 'COPILOT_UPSTREAM_ERROR' });
    });
});
