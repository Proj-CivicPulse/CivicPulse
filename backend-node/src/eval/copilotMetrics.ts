/**
 * Scoring for the Copilot golden-question eval — docs/roadmap.md, 12.5.
 *
 * Pure functions over what the eval recorded: Spring's response to the
 * officer, and (from COPILOT_EVAL_CAPTURE) the packet the model saw and its
 * raw answer before Spring filtered the citations. The three checks the
 * roadmap names:
 *   1. every cited id is in the packet;
 *   2. every number in the answer appears in the packet;
 *   3. unanswerable questions come back answerable: false, citing nothing.
 */

export type Expectation = 'answerable' | 'unanswerable' | 'either';

export interface CapturedAnswer {
    answerable: boolean;
    answer: string;
    citations: string[];
    refused: boolean;
}

export interface SpringAnswer {
    answer: string;
    answerable: boolean;
    refused: boolean;
    ungrounded: boolean;
    droppedCitations: number;
    sources: { id: string }[];
}

export interface QuestionResult {
    id: string;
    question: string;
    expect: Expectation;
    status: number;
    latencyMs: number;
    response?: SpringAnswer;
    packet?: unknown;
    raw?: CapturedAnswer;
    /** Text the answer must contain to count as correct (one of these). */
    mustMention?: string[];
}

export interface Score {
    id: string;
    ok: boolean;
    expectationMet: boolean;
    inventedCitations: string[];
    numbers: number[];
    ungroundedNumbers: number[];
    mentionMet: boolean | null;
    words: number;
    notes: string[];
}

const ID_TOKEN = /\b(?:incident|ward|category|metric):[\w-]+/g;

/** Every "id" string anywhere in the packet. */
export function packetIds(packet: unknown): Set<string> {
    const ids = new Set<string>();
    const walk = (v: unknown): void => {
        if (Array.isArray(v)) v.forEach(walk);
        else if (v && typeof v === 'object') {
            for (const [k, child] of Object.entries(v)) {
                if (k === 'id' && typeof child === 'string') ids.add(child);
                walk(child);
            }
        }
    };
    walk(packet);
    return ids;
}

/**
 * Every number written anywhere in the packet, including inside strings: a
 * priority reason says "14 complaints reported", a date says 2026-10-05, a
 * ward label says "(ward 48)". A number the answer repeats from any of those
 * is grounded.
 */
export function packetNumbers(packet: unknown): number[] {
    return numbersIn(JSON.stringify(packet ?? null));
}

/** Numbers in free text: "1,234" is one number, "15th" is 15; ids are skipped. */
export function numbersIn(text: string): number[] {
    const cleaned = text.replace(ID_TOKEN, ' ').replace(/(\d),(?=\d{3}\b)/g, '$1');
    return [...cleaned.matchAll(/(?<![\w.])-?\d+(?:\.\d+)?/g)].map((m) => Number(m[0]));
}

/**
 * Grounded if the packet holds the same number, or one that rounds to it at
 * the precision the answer used (the packet has 5.53, the answer says 5.5).
 */
export function isGroundedNumber(n: number, packetNums: readonly number[]): boolean {
    const decimals = (String(n).split('.')[1] ?? '').length;
    const factor = 10 ** decimals;
    return packetNums.some((p) => p === n || Math.round(p * factor) / factor === n);
}

const NUMBER_WORDS = [
    'zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten',
    'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen', 'twenty',
];

/** "Zero incidents" and "0 incidents" state the same fact; mention checks read both. */
export function wordsToDigits(text: string): string {
    return text.replace(/\b[a-z]+\b/gi, (w) => {
        const i = NUMBER_WORDS.indexOf(w.toLowerCase());
        return i === -1 ? w : String(i);
    });
}

export function wordCount(text: string): number {
    return text.trim().split(/\s+/).filter(Boolean).length;
}

export function scoreQuestion(r: QuestionResult): Score {
    const notes: string[] = [];
    const empty = { inventedCitations: [], numbers: [], ungroundedNumbers: [], mentionMet: null, words: 0 };

    // Protocol cases: the right HTTP status is the whole test.
    if (r.status !== 200 || !r.response) {
        const ok = r.expect === 'unanswerable' && r.status >= 400 && r.status < 500;
        if (!ok) notes.push(`HTTP ${r.status}`);
        return { id: r.id, ok, expectationMet: ok, ...empty, notes };
    }

    const res = r.response;
    const answerableOut = res.answerable && !res.refused;
    let expectationMet =
        r.expect === 'either' ||
        (r.expect === 'answerable' && answerableOut) ||
        (r.expect === 'unanswerable' && !answerableOut);
    if (r.expect === 'unanswerable' && res.sources.length > 0 && !answerableOut) {
        notes.push('declined but still cited sources');
        expectationMet = false;
    }
    if (!expectationMet) notes.push(`expected ${r.expect}, got answerable=${res.answerable}${res.refused ? ' (refused)' : ''}`);

    const ids = r.packet ? packetIds(r.packet) : null;
    const inventedCitations = r.raw && ids ? r.raw.citations.filter((c) => !ids.has(c)) : [];
    if (inventedCitations.length > 0) notes.push(`cited ids not in the packet: ${inventedCitations.join(', ')}`);
    if (res.droppedCitations > 0 && inventedCitations.length === 0) notes.push(`Spring dropped ${res.droppedCitations} citation(s)`);
    if (answerableOut && res.ungrounded) notes.push('answered without a valid citation (ungrounded)');

    const numbers = numbersIn(res.answer);
    const pnums = r.packet ? packetNumbers(r.packet) : null;
    const ungroundedNumbers = pnums ? numbers.filter((n) => !isGroundedNumber(n, pnums)) : [];
    if (!pnums) notes.push('no captured packet: numbers not checked');
    if (ungroundedNumbers.length > 0) notes.push(`numbers not in the packet: ${ungroundedNumbers.join(', ')}`);

    let mentionMet: boolean | null = null;
    if (r.mustMention && r.mustMention.length > 0 && answerableOut) {
        const text = wordsToDigits(res.answer).toLowerCase();
        mentionMet = r.mustMention.some((m) => new RegExp(`(?<![\\w.])${escapeRegExp(m.toLowerCase())}(?![\\w])`).test(text));
        if (!mentionMet) notes.push(`expected the answer to mention one of: ${r.mustMention.join(' | ')}`);
    }

    const words = wordCount(res.answer);
    if (words > 170) notes.push(`${words} words (rule: about 150)`);

    const ok =
        expectationMet &&
        inventedCitations.length === 0 &&
        ungroundedNumbers.length === 0 &&
        !(answerableOut && res.ungrounded) &&
        mentionMet !== false;
    return { id: r.id, ok, expectationMet, inventedCitations, numbers, ungroundedNumbers, mentionMet, words, notes };
}

function escapeRegExp(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export interface Summary {
    questions: number;
    passed: number;
    answerableAsked: number;
    answerableAnswered: number;
    unanswerableAsked: number;
    unanswerableDeclined: number;
    citationsChecked: number;
    inventedCitations: number;
    numbersChecked: number;
    ungroundedNumbers: number;
    mentionsChecked: number;
    mentionsMet: number;
    medianLatencyMs: number | null;
}

export function summarize(results: readonly QuestionResult[], scores: readonly Score[]): Summary {
    const byId = new Map(scores.map((s) => [s.id, s]));
    const modelCalls = results.filter((r) => r.status === 200 && r.response);
    const latencies = modelCalls.map((r) => r.latencyMs).sort((a, b) => a - b);
    const answerable = results.filter((r) => r.expect === 'answerable');
    const unanswerable = results.filter((r) => r.expect === 'unanswerable');
    const s = (r: QuestionResult) => byId.get(r.id)!;
    return {
        questions: results.length,
        passed: scores.filter((x) => x.ok).length,
        answerableAsked: answerable.length,
        answerableAnswered: answerable.filter((r) => s(r).expectationMet).length,
        unanswerableAsked: unanswerable.length,
        unanswerableDeclined: unanswerable.filter((r) => s(r).expectationMet).length,
        citationsChecked: results.reduce((n, r) => n + (r.raw?.citations.length ?? 0), 0),
        inventedCitations: scores.reduce((n, x) => n + x.inventedCitations.length, 0),
        numbersChecked: scores.reduce((n, x) => n + x.numbers.length, 0),
        ungroundedNumbers: scores.reduce((n, x) => n + x.ungroundedNumbers.length, 0),
        mentionsChecked: scores.filter((x) => x.mentionMet !== null).length,
        mentionsMet: scores.filter((x) => x.mentionMet === true).length,
        medianLatencyMs: latencies[Math.floor(latencies.length / 2)] ?? null,
    };
}
