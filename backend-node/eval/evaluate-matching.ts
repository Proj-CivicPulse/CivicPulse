/* eslint-disable no-console -- a command-line report: printing is its output */
/**
 * Phase 7 matching evaluation (post-midsem roadmap §17).
 *
 *   npm run eval:matching            # from backend-node/
 *
 * Embeds every complaint in eval/matching-eval-set.json with the PRODUCTION
 * embedding call and text builder (same model, dimensions and task type, from
 * the same env), then measures the production decision rule against the
 * labels at a sweep of thresholds:
 *
 *   1. pairwise    — every same-pool pair judged by similarity alone
 *   2. clustering  — arrivals replayed through single-linkage, 25 seeded orders
 *                    per pool, so chaining shows up as it would in production
 *   3. the diameter-floor mitigation from matching-contract.md §4
 *
 * Embeddings are cached in eval/.cache keyed by model + settings + text, so a
 * re-run costs nothing and a changed setting cannot silently reuse stale
 * vectors. Writes eval/results/matching-<version>.json and prints the tables
 * that docs/matching-evaluation.md reports.
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { env } from '../src/config/env.ts';
import { buildEmbeddingText, generateEmbedding } from '../src/services/embedding.service.ts';
import {
    addConfusion,
    clusterConfusion,
    mixedClusters,
    pairConfusion,
    percentile,
    score,
    seededShuffle,
    similarityMatrix,
    simulateClustering,
    type Confusion,
    type LabelledComplaint,
} from '../src/eval/matchingMetrics.ts';

const here = dirname(fileURLToPath(import.meta.url));
const ORDERS = 25;
const THRESHOLDS = Array.from({ length: 41 }, (_, i) => Math.round((0.55 + i * 0.01) * 100) / 100);
const PRODUCTION = env.MATCH_SIMILARITY_THRESHOLD;
/**
 * EVAL_TEXT=production (default) embeds exactly what production does,
 * "[category] description". EVAL_TEXT=plain drops the category prefix — an
 * ablation, to see how much of the similarity floor the shared prefix causes.
 */
const TEXT_MODE = process.env.EVAL_TEXT === 'plain' ? 'plain' : 'production';
const FLOORS = [0.45, 0.5, 0.55, 0.6];

interface EvalSet {
    version: string;
    scenarios: {
        id: string;
        category: string;
        problems: { id: string; summary: string; complaints: string[] }[];
    }[];
}

const set = JSON.parse(readFileSync(join(here, 'matching-eval-set.json'), 'utf8')) as EvalSet;

// ── Embeddings, cached ────────────────────────────────────────────────────

const settings = `${env.EMBEDDING_MODEL}|${env.EMBEDDING_DIMENSIONS}|${env.EMBEDDING_TASK_TYPE}`;
const cacheDir = join(here, '.cache');
const cacheFile = join(cacheDir, 'embeddings.json');
mkdirSync(cacheDir, { recursive: true });
const cache: Record<string, number[]> = existsSync(cacheFile) ? JSON.parse(readFileSync(cacheFile, 'utf8')) : {};

async function embed(text: string): Promise<number[]> {
    const key = createHash('sha256').update(`${settings}\n${text}`).digest('hex');
    const hit = cache[key];
    if (hit) return hit;
    for (let attempt = 1; ; attempt++) {
        try {
            const vector = await generateEmbedding(text);
            cache[key] = vector;
            writeFileSync(cacheFile, JSON.stringify(cache));
            return vector;
        } catch (err) {
            if (attempt >= 5) throw err;
            // Free-tier rate limits: back off and retry rather than fail the run.
            await new Promise((r) => setTimeout(r, 2000 * attempt));
        }
    }
}

// ── Pools ─────────────────────────────────────────────────────────────────

interface Pool {
    id: string;
    complaints: LabelledComplaint[];
    sim: number[][];
}

const pools: Pool[] = [];
let embedded = 0;
for (const scenario of set.scenarios) {
    const complaints: LabelledComplaint[] = scenario.problems.flatMap((p) =>
        p.complaints.map((text, i) => ({ id: `${scenario.id}/${p.id}/${i}`, problemId: p.id, text })),
    );
    const vectors: number[][] = [];
    for (const c of complaints) {
        // Production embeds "[category] description" (no title); plain is the ablation.
        const text =
            TEXT_MODE === 'production'
                ? buildEmbeddingText({ category: scenario.category, title: null, description: c.text })
                : c.text;
        vectors.push(await embed(text));
        embedded++;
    }
    pools.push({ id: scenario.id, complaints, sim: similarityMatrix(vectors) });
}
console.error(`embedded ${embedded} complaints with ${settings}, text=${TEXT_MODE}`);

// By construction the 4th complaint of every problem is the code-mixed one.
const isMixed = (c: LabelledComplaint) => c.id.endsWith('/3');
type LangPair = 'english-english' | 'english-mixed' | 'mixed-mixed';
const langPair = (a: LabelledComplaint, b: LabelledComplaint): LangPair =>
    isMixed(a) && isMixed(b) ? 'mixed-mixed' : isMixed(a) || isMixed(b) ? 'english-mixed' : 'english-english';

// ── 1. Score distributions ────────────────────────────────────────────────

const positives: number[] = [];
const negatives: number[] = [];
const byLanguage: Record<LangPair, { same: number[]; different: number[] }> = {
    'english-english': { same: [], different: [] },
    'english-mixed': { same: [], different: [] },
    'mixed-mixed': { same: [], different: [] },
};
const hardestNegatives: { pool: string; a: string; b: string; sim: number }[] = [];
const weakestPositives: { pool: string; a: string; b: string; sim: number }[] = [];
for (const pool of pools) {
    for (let i = 0; i < pool.complaints.length; i++) {
        for (let j = i + 1; j < pool.complaints.length; j++) {
            const a = pool.complaints[i]!;
            const b = pool.complaints[j]!;
            const s = pool.sim[i]![j]!;
            const row = { pool: pool.id, a: a.text, b: b.text, sim: s };
            byLanguage[langPair(a, b)][a.problemId === b.problemId ? 'same' : 'different'].push(s);
            if (a.problemId === b.problemId) {
                positives.push(s);
                weakestPositives.push(row);
            } else {
                negatives.push(s);
                hardestNegatives.push(row);
            }
        }
    }
}
hardestNegatives.sort((x, y) => y.sim - x.sim);
weakestPositives.sort((x, y) => x.sim - y.sim);

const fmt = (n: number) => n.toFixed(3);
const distribution = {
    positives: {
        n: positives.length,
        min: Math.min(...positives),
        p5: percentile(positives, 5),
        p10: percentile(positives, 10),
        median: percentile(positives, 50),
    },
    negatives: {
        n: negatives.length,
        median: percentile(negatives, 50),
        p90: percentile(negatives, 90),
        p95: percentile(negatives, 95),
        max: Math.max(...negatives),
    },
};

// ── 2. Pairwise and clustering sweeps ─────────────────────────────────────

const zero = (): Confusion => ({ tp: 0, fp: 0, fn: 0, tn: 0 });

function clusteringAt(threshold: number, floor: number | null) {
    let total = zero();
    let mixed = 0;
    let incidents = 0;
    for (const pool of pools) {
        for (let seed = 1; seed <= ORDERS; seed++) {
            const order = seededShuffle(pool.complaints.length, seed * 7919 + pool.id.length);
            const clusterOf = simulateClustering(order, pool.sim, threshold, floor);
            total = addConfusion(total, clusterConfusion(pool.complaints, clusterOf));
            mixed += mixedClusters(pool.complaints, clusterOf);
            incidents += new Set(clusterOf).size;
        }
    }
    const runs = pools.length * ORDERS;
    return {
        ...score(total),
        mixedIncidentsPerPool: mixed / runs,
        incidentsPerPool: incidents / runs,
    };
}

const sweep = THRESHOLDS.map((t) => {
    let pair = zero();
    for (const pool of pools) pair = addConfusion(pair, pairConfusion(pool.complaints, pool.sim, t));
    return { threshold: t, pairwise: score(pair), clustering: clusteringAt(t, null) };
});

const floors = FLOORS.map((floor) => ({ floor, clustering: clusteringAt(PRODUCTION, floor) }));

const best = (key: 'f1' | 'f2', view: 'pairwise' | 'clustering') =>
    sweep.reduce((a, b) => (b[view][key] > a[view][key] ? b : a));

// ── Output ────────────────────────────────────────────────────────────────

const languageSummary = Object.fromEntries(
    Object.entries(byLanguage).map(([k, v]) => [
        k,
        {
            sameN: v.same.length,
            sameMedian: v.same.length ? percentile(v.same, 50) : null,
            sameMin: v.same.length ? Math.min(...v.same) : null,
            differentN: v.different.length,
            differentMedian: percentile(v.different, 50),
            differentMax: Math.max(...v.different),
        },
    ]),
);

const result = {
    version: set.version,
    settings,
    textMode: TEXT_MODE,
    languageSummary,
    productionThreshold: PRODUCTION,
    pools: pools.length,
    complaints: embedded,
    truthIncidentsPerPool: 3,
    distribution,
    hardestNegatives: hardestNegatives.slice(0, 8),
    weakestPositives: weakestPositives.slice(0, 8),
    sweep,
    floors,
    bestPairwiseF1: best('f1', 'pairwise').threshold,
    bestClusteringF1: best('f1', 'clustering').threshold,
    bestClusteringF2: best('f2', 'clustering').threshold,
};
const resultsDir = join(here, 'results');
mkdirSync(resultsDir, { recursive: true });
writeFileSync(
    join(resultsDir, `matching-${set.version}${TEXT_MODE === 'plain' ? '-plain' : ''}.json`),
    JSON.stringify(result, null, 2),
);

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;
console.log(`\nSettings: ${settings}; text=${TEXT_MODE}; production threshold ${PRODUCTION}`);
console.log(`${pools.length} pools, ${embedded} complaints, ${positives.length} same-problem pairs, ${negatives.length} different-problem pairs\n`);
console.log('Similarity distribution');
console.log(`  same problem:      min ${fmt(distribution.positives.min)}  p5 ${fmt(distribution.positives.p5)}  p10 ${fmt(distribution.positives.p10)}  median ${fmt(distribution.positives.median)}`);
console.log(`  different problem: median ${fmt(distribution.negatives.median)}  p90 ${fmt(distribution.negatives.p90)}  p95 ${fmt(distribution.negatives.p95)}  max ${fmt(distribution.negatives.max)}\n`);
console.log('| threshold | pair P | pair R | pair F1 | cluster P | cluster R | cluster F1 | cluster F2 | incidents/pool (truth 3) | mixed/pool |');
console.log('|---|---|---|---|---|---|---|---|---|---|');
for (const row of sweep.filter((r) => Math.round(r.threshold * 100) % 5 === 0 || r.threshold === PRODUCTION)) {
    const p = row.pairwise;
    const c = row.clustering;
    console.log(`| ${row.threshold.toFixed(2)}${row.threshold === PRODUCTION ? ' (prod)' : ''} | ${pct(p.precision)} | ${pct(p.recall)} | ${p.f1.toFixed(3)} | ${pct(c.precision)} | ${pct(c.recall)} | ${c.f1.toFixed(3)} | ${c.f2.toFixed(3)} | ${c.incidentsPerPool.toFixed(2)} | ${c.mixedIncidentsPerPool.toFixed(2)} |`);
}
console.log(`\nBest pairwise F1 at ${result.bestPairwiseF1}; best clustering F1 at ${result.bestClusteringF1}; best clustering F2 at ${result.bestClusteringF2}`);
console.log(`\nDiameter floor at threshold ${PRODUCTION} (clustering):`);
console.log('| floor | P | R | F1 | mixed/pool |');
console.log('|---|---|---|---|---|');
const prodRow = sweep.find((r) => r.threshold === PRODUCTION)!.clustering;
console.log(`| none (prod) | ${pct(prodRow.precision)} | ${pct(prodRow.recall)} | ${prodRow.f1.toFixed(3)} | ${prodRow.mixedIncidentsPerPool.toFixed(2)} |`);
for (const f of floors) {
    console.log(`| ${f.floor} | ${pct(f.clustering.precision)} | ${pct(f.clustering.recall)} | ${f.clustering.f1.toFixed(3)} | ${f.clustering.mixedIncidentsPerPool.toFixed(2)} |`);
}
console.log('\nBy language pairing (median / extreme):');
console.log('| pairing | same problem: median (min) | different problem: median (max) |');
console.log('|---|---|---|');
for (const [k, v] of Object.entries(languageSummary)) {
    console.log(`| ${k} | ${v.sameMedian === null ? '—' : `${fmt(v.sameMedian)} (${fmt(v.sameMin!)})`} | ${fmt(v.differentMedian)} (${fmt(v.differentMax)}) |`);
}
console.log('\nHardest different-problem pairs:');
for (const n of result.hardestNegatives.slice(0, 5)) console.log(`  ${fmt(n.sim)}  [${n.pool}]  "${n.a.slice(0, 60)}" vs "${n.b.slice(0, 60)}"`);
console.log('\nWeakest same-problem pairs:');
for (const p of result.weakestPositives.slice(0, 5)) console.log(`  ${fmt(p.sim)}  [${p.pool}]  "${p.a.slice(0, 60)}" vs "${p.b.slice(0, 60)}"`);
