/* eslint-disable no-console -- a command-line tool: printing is its output */
/**
 * PROXY ranker for the priority calibration — docs/roadmap.md, Phase 8.
 *
 *   npm run eval:priority-proxy -- <ranking-sheet.csv> <out-prefix> [runs=3]
 *
 * Fills the ranking sheet the way an officer would, using a language model
 * that never sees the formula, the scores or the key: only the sheet's
 * columns. Each run shows the rows in a different seeded order under neutral
 * labels (R1, R2, …), so position and id carry no signal, and writes
 * <out-prefix>-<run>.csv in the sheet's format for `npm run eval:priority`.
 * Run-to-run agreement then measures how consistent the ranker is.
 *
 * THIS IS NOT AN OFFICER. It is a stand-in that makes the evaluation runnable
 * and gives the paper a reproducible, clearly-labelled proxy result; it is
 * not grounds to change the production weights (priority-evaluation.md).
 *
 * Uses Gemini (COPILOT_GEMINI_MODEL, GEMINI_API_KEY or EMBEDDING_API_KEY) —
 * billed on the project's Google key; one call per run, a few thousand tokens.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { env } from '../src/config/env.ts';
import { seededShuffle } from '../src/eval/matchingMetrics.ts';
import { parseCsv } from '../src/eval/priorityMetrics.ts';

const [sheetPath, outPrefix, runsArg] = process.argv.slice(2);
if (!sheetPath || !outPrefix) {
    console.error('usage: npm run eval:priority-proxy -- <ranking-sheet.csv> <out-prefix> [runs=3]');
    process.exit(2);
}
const runs = Number(runsArg ?? 3);
const key = env.GEMINI_API_KEY ?? env.EMBEDDING_API_KEY;
const model = env.COPILOT_GEMINI_MODEL;

const rows = parseCsv(readFileSync(sheetPath, 'utf8'));
const header = readFileSync(sheetPath, 'utf8').split(/\r?\n/)[0] ?? '';

const SYSTEM = `You are a senior assistant engineer in a Bengaluru ward office (BBMP).
Each morning you decide which civic problems to send a crew to first.

You get today's active incidents. Each groups one or more citizen reports about
the same problem. For each you see: category, ward, place, total reports,
reports in the last 24 hours and 7 days, days since it opened, and how far apart
the reports are (spread, metres).

Rank ALL of them, most urgent first, the way you actually would: weigh public
safety, how many people are affected, whether it is getting worse, and how long
it has waited. Use only what is given. Every label must appear exactly once.`;

const schema = {
    type: 'OBJECT',
    properties: {
        order: { type: 'ARRAY', items: { type: 'STRING' } },
        rationale: { type: 'STRING' },
    },
    required: ['order', 'rationale'],
};

function describe(r: Record<string, string>, label: string): string {
    const place = (r.place ?? '').split(',').slice(0, 2).join(',').trim() || 'unknown place';
    return `${label}: ${r.category} | ${r.ward} | ${place} | reports ${r.reports} | last 24 h ${r.reports_last_24h} | last 7 d ${r.reports_last_7d} | open ${r.days_open} days | spread ${r.spread_m} m`;
}

async function rankOnce(seed: number): Promise<{ order: string[]; rationale: string }> {
    const order = seededShuffle(rows.length, seed);
    const labelled = order.map((i, k) => ({ label: `R${k + 1}`, row: rows[i]! }));
    const prompt = `${labelled.map((l) => describe(l.row, l.label)).join('\n')}\n\nReturn every label, most urgent first, and a rationale of at most 60 words on what drove your order.`;

    for (let attempt = 1; attempt <= 2; attempt++) {
        const response = await fetch(
            `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(key)}`,
            {
                method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({
                    systemInstruction: { parts: [{ text: SYSTEM }] },
                    contents: [{ role: 'user', parts: [{ text: prompt }] }],
                    generationConfig: {
                        temperature: 0,
                        responseMimeType: 'application/json',
                        responseSchema: schema,
                    },
                }),
            }
        );
        if (!response.ok) throw new Error(`Gemini ${response.status}: ${(await response.text()).slice(0, 300)}`);
        const data = (await response.json()) as { candidates?: { content?: { parts?: { text?: string }[] } }[] };
        const text = data.candidates?.[0]?.content?.parts?.[0]?.text ?? '';
        const parsed = JSON.parse(text) as { order: string[]; rationale: string };

        const byLabel = new Map(labelled.map((l) => [l.label, l.row.incident_id ?? '']));
        const valid = parsed.order.length === labelled.length
            && new Set(parsed.order).size === labelled.length
            && parsed.order.every((l) => byLabel.has(l));
        if (valid) return { order: parsed.order.map((l) => byLabel.get(l)!), rationale: parsed.rationale };
        console.warn(`  run seed ${seed}: attempt ${attempt} did not return a permutation of the labels; retrying`);
    }
    throw new Error(`seed ${seed}: no valid ranking after 2 attempts`);
}

const field = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
const columns = header.split(',').map((c) => c.trim());

for (let run = 1; run <= runs; run++) {
    const { order, rationale } = await rankOnce(1000 + run);
    const rank = new Map(order.map((id, i) => [id, i + 1]));
    const lines = [columns.join(',')];
    for (const r of rows) {
        lines.push(columns.map((c) => (c === 'officer_rank' ? String(rank.get(r.incident_id ?? '') ?? '') : field(r[c] ?? ''))).join(','));
    }
    const out = `${outPrefix}-${run}.csv`;
    writeFileSync(out, `${lines.join('\n')}\n`);
    console.log(`run ${run} → ${out}\n  rationale: ${rationale}`);
}
console.log(`\nmodel ${model}; ${rows.length} incidents; ${runs} runs. Score with: npm run eval:priority -- <key.csv> ${outPrefix}-*.csv`);
