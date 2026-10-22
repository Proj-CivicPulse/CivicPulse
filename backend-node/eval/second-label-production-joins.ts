/* eslint-disable no-console -- a command-line tool: printing is its output */
/**
 * Independent SECOND LABELLER for eval/production-joins-labelled.json.
 *
 *   npm run eval:second-label -- <out.json>
 *
 * Shows a language model each pair under the same label rule the first
 * labeller used — category, the two texts, how far apart the reports were —
 * and never the first label, its reason or the similarity score. Then prints
 * raw agreement and Cohen's kappa against the stored labels, and lists every
 * disagreement for a person to adjudicate.
 *
 * A second opinion, not a ground truth: the adjudicated label stays the one
 * in the labelled file, and its reason says when a disagreement changed it.
 *
 * Uses Gemini (COPILOT_GEMINI_MODEL, GEMINI_API_KEY or EMBEDDING_API_KEY) —
 * billed on the project's Google key; one call, a few thousand tokens.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { env } from '../src/config/env.ts';

type Label = 'same' | 'different' | 'ambiguous';
interface Pair {
    id: string;
    category: string;
    distancesM: number[];
    a: string;
    b: string;
    label: Label;
}

const [outPath] = process.argv.slice(2);
if (!outPath) {
    console.error('usage: npm run eval:second-label -- <out.json>');
    process.exit(2);
}

const here = dirname(fileURLToPath(import.meta.url));
const { pairs } = JSON.parse(readFileSync(join(here, 'production-joins-labelled.json'), 'utf8')) as { pairs: Pair[] };
const key = env.GEMINI_API_KEY ?? env.EMBEDDING_API_KEY;
const model = env.COPILOT_GEMINI_MODEL;

const SYSTEM = `You label pairs of citizen complaints for a city's complaint-grouping system.
Two reports were automatically grouped into one incident. Decide, for each pair:

  same       — the same physical fault, fixable by one repair job
  different  — two faults needing separate jobs (different spot, cause or crew)
  ambiguous  — plausibly one root cause, but a careful person could not be sure

Use only the texts, the category and the distance between the two reports.
Distances are between report locations; several values mean the pair was
grouped more than once. Label every pair exactly once.`;

const schema = {
    type: 'OBJECT',
    properties: {
        labels: {
            type: 'ARRAY',
            items: {
                type: 'OBJECT',
                properties: {
                    id: { type: 'STRING' },
                    label: { type: 'STRING', enum: ['same', 'different', 'ambiguous'] },
                    reason: { type: 'STRING' },
                },
                required: ['id', 'label', 'reason'],
            },
        },
    },
    required: ['labels'],
};

const prompt = pairs
    .map((p) => {
        const lo = Math.min(...p.distancesM);
        const hi = Math.max(...p.distancesM);
        const distance = lo === hi ? `${lo} m` : `${lo}–${hi} m`;
        return `${p.id} | ${p.category} | ${distance} apart\n  A: ${p.a}\n  B: ${p.b}`;
    })
    .join('\n');

const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(key)}`,
    {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
            systemInstruction: { parts: [{ text: SYSTEM }] },
            contents: [{ role: 'user', parts: [{ text: `${prompt}\n\nReturn a label and a one-sentence reason for every id.` }] }],
            generationConfig: { temperature: 0, responseMimeType: 'application/json', responseSchema: schema },
        }),
    }
);
if (!response.ok) throw new Error(`Gemini ${response.status}: ${(await response.text()).slice(0, 300)}`);
const data = (await response.json()) as { candidates?: { content?: { parts?: { text?: string }[] } }[] };
const { labels } = JSON.parse(data.candidates?.[0]?.content?.parts?.[0]?.text ?? '{}') as {
    labels: { id: string; label: Label; reason: string }[];
};

const second = new Map(labels.map((l) => [l.id, l]));
const missing = pairs.filter((p) => !second.has(p.id)).map((p) => p.id);
if (missing.length > 0) throw new Error(`second labeller skipped ${missing.join(', ')}`);

// Cohen's kappa over the three classes.
const classes: Label[] = ['same', 'different', 'ambiguous'];
const n = pairs.length;
const agree = pairs.filter((p) => second.get(p.id)!.label === p.label).length;
const po = agree / n;
const pe = classes.reduce((sum, c) => {
    const first = pairs.filter((p) => p.label === c).length / n;
    const other = pairs.filter((p) => second.get(p.id)!.label === c).length / n;
    return sum + first * other;
}, 0);
const kappa = pe === 1 ? 1 : (po - pe) / (1 - pe);

// The headline figures exclude ambiguous pairs, so the disagreement that
// matters most is a same/different flip.
const flips = pairs.filter((p) => {
    const s = second.get(p.id)!.label;
    return s !== p.label && s !== 'ambiguous' && p.label !== 'ambiguous';
});

writeFileSync(outPath, `${JSON.stringify({ model, labels }, null, 2)}\n`);
console.log(`model ${model}; ${n} pairs`);
console.log(`agreement ${agree}/${n} (${(po * 100).toFixed(0)}%), Cohen's kappa ${kappa.toFixed(2)}`);
console.log(`same ↔ different flips: ${flips.length}\n`);
for (const p of pairs) {
    const s = second.get(p.id)!;
    if (s.label !== p.label) console.log(`${p.id}: first ${p.label}, second ${s.label} — ${s.reason}`);
}
console.log(`\nwritten ${outPath}`);
