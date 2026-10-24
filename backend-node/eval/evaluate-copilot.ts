/* eslint-disable no-console -- a command-line tool: printing is its output */
/**
 * Copilot golden-question eval — docs/roadmap.md, 12.5.
 *
 *   npm run eval:copilot                      # ask every question, then score
 *   npm run eval:copilot -- --score <dir>     # re-score a saved run; no model calls
 *
 * Asks eval/copilot-questions.json through backend-spring's POST
 * /copilot/query as an officer — the same path the panel uses — and scores
 * each answer against the grounding packet the model saw (src/eval/copilotMetrics.ts).
 *
 * Needs, besides both services running against a seeded LOCAL database:
 *   EVAL_OFFICER_EMAIL / EVAL_OFFICER_PASSWORD   an officer account
 *   EVAL_CORE_URL                                backend-spring (default http://localhost:8080)
 *   COPILOT_EVAL_CAPTURE in backend-node/.env    the file backend-node appends packets to;
 *                                                backend-node must have started with it set
 *
 * Billed: one model call per question (21 on the default set). Spring allows
 * 30 questions per officer per hour, so one run fits; a second needs a wait.
 * Writes eval/results/copilot-<date>/raw.json and report.md (EVAL_OUT overrides the folder name).
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { env } from '../src/config/env.ts';
import {
    type Expectation,
    type QuestionResult,
    type SpringAnswer,
    scoreQuestion,
    summarize,
} from '../src/eval/copilotMetrics.ts';

interface GoldenQuestion {
    id: string;
    scope: string;
    expect: Expectation;
    mention?: string;
    question: string;
}

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const scoreOnly = args[0] === '--score' ? args[1] : undefined;

const today = new Date().toISOString().slice(0, 10);
// EVAL_OUT names the run's folder, so a second run on the same day keeps the first.
const outDir = scoreOnly ?? path.join(HERE, 'results', process.env.EVAL_OUT ?? `copilot-${today}`);

let results: (QuestionResult & { scope: string; mention?: string })[];
let meta: Record<string, unknown>;

if (scoreOnly) {
    ({ results, meta } = JSON.parse(readFileSync(path.join(scoreOnly, 'raw.json'), 'utf8')));
} else {
    ({ results, meta } = await ask());
    mkdirSync(outDir, { recursive: true });
    writeFileSync(path.join(outDir, 'raw.json'), JSON.stringify({ meta, results }, null, 2));
}

for (const r of results) r.mustMention = r.mention ? resolveMention(r.mention, r.packet) : undefined;
const scores = results.map(scoreQuestion);
const summary = summarize(results, scores);
const report = renderReport(results, scores, summary, meta);
writeFileSync(path.join(outDir, 'report.md'), report);
console.log(report);

// ───────────────────────────────────────────────────────────────── asking

async function ask() {
    const core = process.env.EVAL_CORE_URL ?? 'http://localhost:8080';
    const email = process.env.EVAL_OFFICER_EMAIL;
    const password = process.env.EVAL_OFFICER_PASSWORD;
    const capture = env.COPILOT_EVAL_CAPTURE;
    if (!email || !password) fail('set EVAL_OFFICER_EMAIL and EVAL_OFFICER_PASSWORD');
    if (!capture) fail('set COPILOT_EVAL_CAPTURE in backend-node/.env and restart backend-node');

    const login = await fetch(`${core}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
    });
    if (!login.ok) fail(`login failed: HTTP ${login.status}`);
    const cookie = login.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');

    const wardsRes = await fetch(`${core}/dashboard/wards`, { headers: { cookie } });
    if (!wardsRes.ok) fail(`GET /dashboard/wards: HTTP ${wardsRes.status} (is this an officer account?)`);
    const wards = (await wardsRes.json()) as { wardId: string; wardName: string; activeIncidentCount: number }[];
    const busiest = [...wards].sort((a, b) => b.activeIncidentCount - a.activeIncidentCount);
    const [busy, other] = busiest;
    if (!busy || !other || busy.activeIncidentCount === 0) fail('the local database needs incidents in at least two wards (run the seed)');

    const golden = JSON.parse(readFileSync(path.join(HERE, 'copilot-questions.json'), 'utf8')).questions as GoldenQuestion[];
    const out: (QuestionResult & { scope: string; mention?: string })[] = [];

    for (const q of golden) {
        const question = q.question.replace('{otherWard}', other.wardName);
        const body: Record<string, string> = { question };
        if (q.scope === 'busyWard') body.wardId = String(busy.wardId);
        if (q.scope === 'badWard') body.wardId = '999999';
        if (q.scope.startsWith('category:')) body.category = q.scope.slice('category:'.length);

        const before = captureLines(capture!).length;
        const started = Date.now();
        const res = await fetch(`${core}/copilot/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', cookie },
            body: JSON.stringify(body),
        });
        const latencyMs = Date.now() - started;
        const json = (await res.json().catch(() => null)) as SpringAnswer | null;

        const added = captureLines(capture!).slice(before).map((l) => JSON.parse(l));
        const captured = added.reverse().find((c) => c.question === question);

        out.push({
            id: q.id,
            scope: q.scope,
            mention: q.mention,
            question,
            expect: q.expect,
            status: res.status,
            latencyMs,
            response: res.ok && json ? json : undefined,
            packet: captured?.packet,
            raw: captured?.answer,
        });
        console.log(`${q.id.padEnd(4)} ${res.status} ${String(latencyMs).padStart(6)} ms  ${question}`);
        if (res.status === 429) fail('rate limited (30 questions per officer per hour); re-run later');
    }

    return {
        results: out,
        meta: {
            date: new Date().toISOString(),
            provider: env.COPILOT_PROVIDER,
            model: out.find((r) => r.response)?.response && (out.find((r) => r.response)!.response as unknown as { model?: string }).model,
            busyWard: `${busy.wardName} (id ${busy.wardId}, ${busy.activeIncidentCount} active)`,
            otherWard: other.wardName,
        },
    };
}

function captureLines(file: string): string[] {
    if (!existsSync(file)) return [];
    return readFileSync(file, 'utf8').split('\n').filter((l) => l.trim().length > 0);
}

function fail(message: string): never {
    console.error(`eval:copilot — ${message}`);
    process.exit(2);
}

// ──────────────────────────────────────────────────── expected mentions

interface PacketShape {
    metrics?: { id: string; value: unknown }[];
    activeIncidents?: { items?: { name: string; priorityReasons?: string[] }[] };
    categories?: { name: string; activeIncidents: number }[];
    busiestWards?: { name: string; activeIncidents: number }[];
    hotspots?: { items?: unknown[] };
    needsAttention?: { signals?: { key: string; count: number }[] };
}

/** What the answer must contain, read from the packet the model was given. */
function resolveMention(key: string, packet: unknown): string[] | undefined {
    const p = (packet ?? {}) as PacketShape;
    if (key.startsWith('metric:')) {
        const v = p.metrics?.find((m) => m.id === key)?.value;
        return typeof v === 'number' ? [String(v)] : undefined;
    }
    const top = p.activeIncidents?.items?.[0];
    switch (key) {
        case 'topIncidentPlace': {
            const place = top?.name.split(' · ')[1];
            return place ? [place] : undefined;
        }
        case 'topIncidentReason':
            // Any one stated reason, by its number ("14 complaints reported" → "14").
            return top?.priorityReasons?.map((r) => r.match(/\d+/)?.[0]).filter((x): x is string => !!x);
        case 'topCategoryByActive': {
            const c = [...(p.categories ?? [])].sort((a, b) => b.activeIncidents - a.activeIncidents)[0];
            return c ? [c.name, c.name.split(/[ /]/)[0]] : undefined;
        }
        case 'hotspotCount':
            return p.hotspots?.items ? [String(p.hotspots.items.length)] : undefined;
        case 'lifeSafetyCount': {
            const n = p.needsAttention?.signals?.find((x) => x.key === 'life_safety')?.count;
            return typeof n === 'number' ? [String(n)] : undefined;
        }
        case 'topWard': {
            const w = p.busiestWards?.[0];
            return w ? [w.name.replace(/\s*\(ward [^)]*\)$/, '')] : undefined;
        }
        default:
            return undefined;
    }
}

// ─────────────────────────────────────────────────────────────── report

function renderReport(
    rs: typeof results,
    sc: ReturnType<typeof scoreQuestion>[],
    s: ReturnType<typeof summarize>,
    m: Record<string, unknown>,
): string {
    const pct = (a: number, b: number) => (b === 0 ? '—' : `${a}/${b} (${Math.round((100 * a) / b)}%)`);
    const lines = [
        `# Copilot golden-question eval — ${String(m.date ?? '').slice(0, 10)}`,
        '',
        `Provider \`${m.provider}\`, model \`${m.model ?? '?'}\`. Ward-scoped questions use ${m.busyWard}.`,
        '',
        '| Check | Result |',
        '|---|---|',
        `| Questions passing every check | ${pct(s.passed, s.questions)} |`,
        `| Answerable questions answered | ${pct(s.answerableAnswered, s.answerableAsked)} |`,
        `| Unanswerable questions declined, citing nothing | ${pct(s.unanswerableDeclined, s.unanswerableAsked)} |`,
        `| Citations not in the packet | ${s.inventedCitations} of ${s.citationsChecked} |`,
        `| Numbers not in the packet | ${s.ungroundedNumbers} of ${s.numbersChecked} |`,
        `| Expected fact mentioned | ${pct(s.mentionsMet, s.mentionsChecked)} |`,
        `| Median latency | ${s.medianLatencyMs === null ? '—' : `${(s.medianLatencyMs / 1000).toFixed(1)} s`} |`,
        '',
        '| # | Expect | HTTP | Answerable | Pass | Notes |',
        '|---|---|---|---|---|---|',
        ...rs.map((r, i) => {
            const x = sc[i]!;
            const a = r.response ? (r.response.refused ? 'refused' : String(r.response.answerable)) : '—';
            return `| ${r.id} | ${r.expect} | ${r.status} | ${a} | ${x.ok ? '✅' : '❌'} | ${x.notes.join('; ') || ''} |`;
        }),
        '',
        '## Answers',
        '',
        ...rs.flatMap((r) => [
            `**${r.id}. ${r.question}**${r.scope !== 'city' ? ` _(scope: ${r.scope})_` : ''}`,
            '',
            `> ${(r.response?.answer ?? `HTTP ${r.status}`).replace(/\n+/g, ' ')}`,
            '',
        ]),
    ];
    return lines.join('\n');
}
