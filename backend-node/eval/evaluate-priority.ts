/* eslint-disable no-console -- a command-line report: printing is its output */
/**
 * Priority calibration — docs/roadmap.md, Phase 8.2.
 *
 *   npm run eval:priority -- <ranking-key.csv> <ranked-sheet.csv> [more ranked sheets…]
 *
 * <ranking-key.csv>    GET /dashboard/ranking-sheet?withScores=true — inputs + score
 * <ranked-sheet.csv>   GET /dashboard/ranking-sheet, with officer_rank filled in
 *                      by the officer (1 = most urgent). Rows left blank are skipped.
 *
 * Fetch the two in the same minute: the age term moves daily. Several ranked
 * sheets (two officers) are each scored, then compared with each other — the
 * agreement between people is the ceiling the formula can be held to.
 *
 * Prints markdown for docs/priority-evaluation.md. Changes nothing.
 */

import { readFileSync } from 'node:fs';
import { evaluateRanking, kendallTauB, parseCsv, type RankedRow } from '../src/eval/priorityMetrics.ts';

const [keyPath, ...sheetPaths] = process.argv.slice(2);
if (!keyPath || sheetPaths.length === 0) {
    console.error('usage: npm run eval:priority -- <ranking-key.csv> <ranked-sheet.csv> [...]');
    process.exit(2);
}

const key = new Map(parseCsv(readFileSync(keyPath, 'utf8')).map((r) => [r.incident_id ?? '', r]));
const num = (v: string | undefined) => (v === undefined || v === '' ? NaN : Number(v));
const fmt = (v: number) => (Number.isFinite(v) ? v.toFixed(3) : 'n/a');

const rankings = new Map<string, Map<string, number>>();

for (const path of sheetPaths) {
    const rows: RankedRow[] = [];
    for (const r of parseCsv(readFileSync(path, 'utf8'))) {
        const id = r.incident_id ?? '';
        const rank = num(r.officer_rank);
        const k = key.get(id);
        if (!Number.isFinite(rank)) continue;
        if (!k) {
            console.warn(`  ${path}: incident ${id} is not in the key — skipped (was the key fetched with this sheet?)`);
            continue;
        }
        rows.push({
            id,
            officerRank: rank,
            reports: num(k.reports),
            reportsLast24h: num(k.reports_last_24h),
            daysOpen: num(k.days_open),
            spreadM: num(k.spread_m),
            keyScore: num(k.score),
        });
    }
    rankings.set(path, new Map(rows.map((r) => [r.id, r.officerRank])));

    const ev = evaluateRanking(rows);
    console.log(`\n## ${path} — ${ev.n} incidents ranked\n`);
    if (ev.port.exact < ev.port.checked) {
        console.log(
            `> Port check: ${ev.port.exact}/${ev.port.checked} key scores reproduced exactly, ` +
                `${ev.port.within01} within 0.1 (spread is exported to the metre). ` +
                'Anything below that means src/eval/priorityMetrics.ts no longer matches PriorityService.\n'
        );
    } else {
        console.log(`Port check: all ${ev.port.checked} key scores reproduced exactly.\n`);
    }
    if (ev.n < 30) console.log(`> Only ${ev.n} ranked — roadmap 8.3 asks for at least 30 before reading much into this.\n`);

    console.log('| | Kendall τ-b vs officer | precision@10 |');
    console.log('|---|---:|---:|');
    console.log(`| **Formula as shipped** | ${fmt(ev.shipped.tau)} | ${fmt(ev.shipped.precisionAt10)} |`);
    for (const [term, tau] of Object.entries(ev.perTerm)) console.log(`| ${term} alone | ${fmt(tau)} | |`);

    console.log('\n| Variant | τ-b | p@10 | τ-b half A | τ-b half B |');
    console.log('|---|---:|---:|---:|---:|');
    for (const v of ev.variants) {
        console.log(`| ${v.name} | ${fmt(v.tau)} | ${fmt(v.precisionAt10)} | ${fmt(v.tauHalfA)} | ${fmt(v.tauHalfB)} |`);
    }
    console.log(
        '\nRule (roadmap 8.4): adopt a variant only if it beats "shipped" on BOTH halves — ' +
            'a gain on one half alone is the variant fitting those incidents.'
    );
}

if (rankings.size >= 2) {
    console.log('\n## Officer vs officer\n');
    console.log('| | τ-b | incidents in common |');
    console.log('|---|---:|---:|');
    const entries = [...rankings.entries()];
    for (let i = 0; i < entries.length; i++) {
        for (let j = i + 1; j < entries.length; j++) {
            const [pa, a] = entries[i]!;
            const [pb, b] = entries[j]!;
            const common = [...a.keys()].filter((id) => b.has(id));
            const tau = kendallTauB(
                common.map((id) => -(a.get(id) ?? 0)),
                common.map((id) => -(b.get(id) ?? 0))
            );
            console.log(`| ${pa} vs ${pb} | ${fmt(tau)} | ${common.length} |`);
        }
    }
    console.log('\nThe formula cannot be expected to agree with an officer better than two officers agree with each other.');
}
