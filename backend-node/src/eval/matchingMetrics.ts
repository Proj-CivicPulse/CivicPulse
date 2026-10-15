/**
 * Matching evaluation metrics — Phase 7 (post-midsem roadmap §17).
 *
 * Pure functions over labelled complaints and their similarity matrix, so the
 * maths is unit-tested apart from the embedding calls. The decision rule they
 * reproduce is the production one in matching.service.ts: inside a ward +
 * category pool, a complaint joins the incident whose BEST-matching member
 * clears the threshold (single-linkage), otherwise it starts a new incident.
 */

export interface LabelledComplaint {
    id: string;
    /** Ground truth: complaints with the same problemId are one incident. */
    problemId: string;
    text: string;
}

export interface Confusion {
    tp: number;
    fp: number;
    fn: number;
    tn: number;
}

export interface Scores extends Confusion {
    precision: number;
    recall: number;
    f1: number;
    /** Recall-weighted (β = 2): the contract prefers over-grouping to fragmentation. */
    f2: number;
}

/** Cosine similarity of two unit vectors (production stores L2-normalised vectors). */
export function cosine(a: readonly number[], b: readonly number[]): number {
    let dot = 0;
    for (let i = 0; i < a.length; i++) dot += (a[i] ?? 0) * (b[i] ?? 0);
    return dot;
}

/** n×n similarity matrix for a pool. */
export function similarityMatrix(vectors: readonly (readonly number[])[]): number[][] {
    return vectors.map((a) => vectors.map((b) => cosine(a, b)));
}

export function score(c: Confusion): Scores {
    const precision = c.tp + c.fp === 0 ? 1 : c.tp / (c.tp + c.fp);
    const recall = c.tp + c.fn === 0 ? 1 : c.tp / (c.tp + c.fn);
    const fBeta = (beta: number) => {
        const b2 = beta * beta;
        const denom = b2 * precision + recall;
        return denom === 0 ? 0 : ((1 + b2) * precision * recall) / denom;
    };
    return { ...c, precision, recall, f1: fBeta(1), f2: fBeta(2) };
}

export function addConfusion(a: Confusion, b: Confusion): Confusion {
    return { tp: a.tp + b.tp, fp: a.fp + b.fp, fn: a.fn + b.fn, tn: a.tn + b.tn };
}

/**
 * Every unordered pair in one pool, judged by similarity alone: "same incident"
 * iff sim >= threshold. This is the matcher's decision for two complaints with
 * nothing else in the pool.
 */
export function pairConfusion(
    complaints: readonly LabelledComplaint[],
    sim: readonly (readonly number[])[],
    threshold: number,
): Confusion {
    const c: Confusion = { tp: 0, fp: 0, fn: 0, tn: 0 };
    for (let i = 0; i < complaints.length; i++) {
        for (let j = i + 1; j < complaints.length; j++) {
            const same = complaints[i]!.problemId === complaints[j]!.problemId;
            const predicted = (sim[i]![j] ?? 0) >= threshold;
            if (same && predicted) c.tp++;
            else if (!same && predicted) c.fp++;
            else if (same && !predicted) c.fn++;
            else c.tn++;
        }
    }
    return c;
}

/**
 * Replays arrivals through the production rule and returns a cluster id per
 * complaint (indexed like `complaints`).
 *
 * `diameterFloor`, when set, is the contract's "first mitigation to try" for
 * chaining: a complaint may join only if it also clears the floor against the
 * incident's WORST-matching member. null reproduces production exactly.
 */
export function simulateClustering(
    order: readonly number[],
    sim: readonly (readonly number[])[],
    threshold: number,
    diameterFloor: number | null = null,
): number[] {
    const clusterOf: number[] = new Array(sim.length).fill(-1);
    const members: number[][] = [];

    for (const i of order) {
        let bestCluster = -1;
        let bestScore = -Infinity;
        for (let k = 0; k < members.length; k++) {
            const sims = members[k]!.map((m) => sim[i]![m] ?? 0);
            const top = Math.max(...sims);
            if (diameterFloor !== null && Math.min(...sims) < diameterFloor) continue;
            if (top > bestScore) {
                bestScore = top;
                bestCluster = k;
            }
        }
        if (bestCluster >= 0 && bestScore >= threshold) {
            members[bestCluster]!.push(i);
            clusterOf[i] = bestCluster;
        } else {
            members.push([i]);
            clusterOf[i] = members.length - 1;
        }
    }
    return clusterOf;
}

/**
 * Pairwise clustering quality: a pair is "predicted together" when it ended
 * in one incident — so a pair the rule never compared directly but chained
 * together still counts, which is exactly what makes chaining measurable.
 */
export function clusterConfusion(
    complaints: readonly LabelledComplaint[],
    clusterOf: readonly number[],
): Confusion {
    const c: Confusion = { tp: 0, fp: 0, fn: 0, tn: 0 };
    for (let i = 0; i < complaints.length; i++) {
        for (let j = i + 1; j < complaints.length; j++) {
            const same = complaints[i]!.problemId === complaints[j]!.problemId;
            const together = clusterOf[i] === clusterOf[j];
            if (same && together) c.tp++;
            else if (!same && together) c.fp++;
            else if (same && !together) c.fn++;
            else c.tn++;
        }
    }
    return c;
}

/** Incidents that ended up holding more than one real problem — visible over-grouping. */
export function mixedClusters(complaints: readonly LabelledComplaint[], clusterOf: readonly number[]): number {
    const problemsPerCluster = new Map<number, Set<string>>();
    clusterOf.forEach((cluster, i) => {
        const set = problemsPerCluster.get(cluster) ?? new Set<string>();
        set.add(complaints[i]!.problemId);
        problemsPerCluster.set(cluster, set);
    });
    return [...problemsPerCluster.values()].filter((s) => s.size > 1).length;
}

/** Deterministic PRNG (mulberry32) so arrival orders are reproducible run to run. */
export function seededShuffle(n: number, seed: number): number[] {
    let state = seed >>> 0;
    const next = () => {
        state = (state + 0x6d2b79f5) >>> 0;
        let t = state;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const order = Array.from({ length: n }, (_, i) => i);
    for (let i = n - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [order[i], order[j]] = [order[j]!, order[i]!];
    }
    return order;
}

/** Nearest-rank percentile of a sample (p in 0..100). */
export function percentile(values: readonly number[], p: number): number {
    if (values.length === 0) return NaN;
    const sorted = [...values].sort((a, b) => a - b);
    const rank = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
    return sorted[rank]!;
}
