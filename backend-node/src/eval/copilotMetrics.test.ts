import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
    isGroundedNumber,
    numbersIn,
    packetIds,
    packetNumbers,
    scoreQuestion,
    summarize,
    type QuestionResult,
} from './copilotMetrics.ts';

const PACKET = {
    scope: { ward: 'Peenya (ward 41)' },
    metrics: [{ id: 'metric:active_incidents', label: 'Active incidents in scope', value: 45 }],
    activeIncidents: {
        items: [
            {
                id: 'incident:7',
                name: 'Pothole / road damage · 5th Cross Rd',
                priorityScore: 6.13,
                priorityReasons: ['14 complaints reported', 'Open for 21 days without resolution'],
            },
        ],
    },
};

const answered = (over: Partial<QuestionResult> = {}): QuestionResult => ({
    id: 'q',
    question: 'What is urgent?',
    expect: 'answerable',
    status: 200,
    latencyMs: 8000,
    packet: PACKET,
    raw: { answerable: true, answer: '', citations: ['incident:7'], refused: false },
    response: {
        answer: 'The pothole on 5th Cross Rd (incident:7) is high priority at 6.1: 14 complaints, open 21 days.',
        answerable: true,
        refused: false,
        ungrounded: false,
        droppedCitations: 0,
        sources: [{ id: 'incident:7' }],
    },
    ...over,
});

describe('packet extraction', () => {
    it('collects every id, at any depth', () => {
        assert.deepEqual([...packetIds(PACKET)].sort(), ['incident:7', 'metric:active_incidents']);
    });

    it('finds numbers inside strings as well as values', () => {
        const nums = packetNumbers(PACKET);
        for (const n of [41, 45, 6.13, 14, 21, 5]) assert.ok(nums.includes(n), `missing ${n}`);
    });
});

describe('numbersIn', () => {
    it('reads ordinals, thousands separators and decimals; skips ids', () => {
        assert.deepEqual(numbersIn('15th Cross, 1,234 reports, score 5.5, see incident:45'), [15, 1234, 5.5]);
    });
});

describe('isGroundedNumber', () => {
    it('accepts exact values and values rounded to the precision used', () => {
        assert.equal(isGroundedNumber(6.1, [6.13]), true);
        assert.equal(isGroundedNumber(6, [6.13]), true);
        assert.equal(isGroundedNumber(6.2, [6.13]), false);
        assert.equal(isGroundedNumber(46, [45]), false);
    });
});

describe('scoreQuestion', () => {
    it('passes a grounded answer', () => {
        const s = scoreQuestion(answered());
        assert.equal(s.ok, true, s.notes.join('; '));
        assert.deepEqual(s.ungroundedNumbers, []);
    });

    it('fails a number the packet does not hold', () => {
        const r = answered();
        r.response!.answer = 'There are 46 active incidents (metric:active_incidents).';
        const s = scoreQuestion(r);
        assert.equal(s.ok, false);
        assert.deepEqual(s.ungroundedNumbers, [46]);
    });

    it('fails a citation the packet does not hold, even though Spring dropped it', () => {
        const s = scoreQuestion(answered({ raw: { answerable: true, answer: '', citations: ['incident:999'], refused: false } }));
        assert.equal(s.ok, false);
        assert.deepEqual(s.inventedCitations, ['incident:999']);
    });

    it('passes a declined unanswerable question that cites nothing', () => {
        const s = scoreQuestion(
            answered({
                expect: 'unanswerable',
                response: { answer: 'The data does not cover the weather.', answerable: false, refused: false, ungrounded: false, droppedCitations: 0, sources: [] },
            }),
        );
        assert.equal(s.ok, true, s.notes.join('; '));
    });

    it('fails an unanswerable question that was answered', () => {
        assert.equal(scoreQuestion(answered({ expect: 'unanswerable' })).ok, false);
    });

    it('treats a 4xx as the expected result for an invalid request', () => {
        assert.equal(scoreQuestion({ id: 'bad-ward', question: 'x', expect: 'unanswerable', status: 404, latencyMs: 5 }).ok, true);
        assert.equal(scoreQuestion({ id: 'down', question: 'x', expect: 'answerable', status: 502, latencyMs: 5 }).ok, false);
    });

    it('checks the expected mention when one is given', () => {
        assert.equal(scoreQuestion(answered({ mustMention: ['5th Cross Rd'] })).mentionMet, true);
        assert.equal(scoreQuestion(answered({ mustMention: ['Peenya'] })).ok, false);
    });

    it('reads number words in a mention, and matches whole numbers only', () => {
        const zero = answered();
        zero.response!.answer = 'Zero incidents have been fixed (metric:fixed_incidents).';
        assert.equal(scoreQuestion({ ...zero, mustMention: ['0'] }).mentionMet, true);
        const larger = answered();
        larger.response!.answer = 'There are 145 complaints.';
        assert.equal(scoreQuestion({ ...larger, mustMention: ['45'] }).mentionMet, false);
    });
});

describe('summarize', () => {
    it('counts per expectation', () => {
        const results = [answered({ id: 'a' }), answered({ id: 'b', expect: 'unanswerable' })];
        const sum = summarize(results, results.map(scoreQuestion));
        assert.equal(sum.passed, 1);
        assert.equal(sum.answerableAnswered, 1);
        assert.equal(sum.unanswerableDeclined, 0);
        assert.equal(sum.medianLatencyMs, 8000);
    });
});
