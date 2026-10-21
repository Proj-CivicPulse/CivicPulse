/**
 * Priority calibration metrics — docs/roadmap.md, Phase 8.2.
 *
 * Compares an officer's hand ranking of the active queue with the priority
 * formula. Pure functions, so the maths is unit-tested apart from any data.
 *
 * The formula is ported from backend-spring's PriorityService so variants
 * (another age saturation, another weight) can be scored offline. The port is
 * checked against the scores in the exported key before anything is
 * evaluated — see evaluateRanking — so it cannot drift from the Java silently.
 */

/** The formula's inputs, as exported by GET /dashboard/ranking-sheet. */
export interface PriorityInputs {
    reports: number;
    reportsLast24h: number;
    daysOpen: number;
    spreadM: number;
}

export interface FormulaParams {
    weights: { volume: number; growth: number; age: number; spread: number };
    saturation: { reports: number; reportsPer24h: number; ageDays: number; spreadM: number };
}

/** PriorityService as shipped: 40/30/20/10, saturating at 25 reports, 5/24 h, 14 days, 1 km. */
export const PRODUCTION: FormulaParams = {
    weights: { volume: 0.4, growth: 0.3, age: 0.2, spread: 0.1 },
    saturation: { reports: 25, reportsPer24h: 5, ageDays: 14, spreadM: 1000 },
};

const saturate = (v: number) => (Number.isNaN(v) || v < 0 ? 0 : Math.min(1, v));

/** Each term in [0, 1], before weighting. */
export function terms(x: PriorityInputs, p: FormulaParams = PRODUCTION) {
    return {
        volume: saturate(Math.log10(1 + x.reports) / Math.log10(1 + p.saturation.reports)),
        growth: saturate(x.reportsLast24h / p.saturation.reportsPer24h),
        age: saturate(x.daysOpen / p.saturation.ageDays),
        spread: saturate(x.spreadM / p.saturation.spreadM),
    };
}

/** The 0–10 score, rounded to one decimal exactly as PriorityService does. */
export function priorityScore(x: PriorityInputs, p: FormulaParams = PRODUCTION): number {
    const t = terms(x, p);
    const raw = p.weights.volume * t.volume + p.weights.growth * t.growth + p.weights.age * t.age + p.weights.spread * t.spread;
    return Math.round(raw * 10 * 10) / 10;
}

/**
 * Kendall's τ-b between two scorings of the same items (higher = more urgent
 * in both). Ties are corrected for, which matters: scores round to 0.1 and
 * many incidents share one. Returns NaN when either side is all ties.
 */
export function kendallTauB(a: readonly number[], b: readonly number[]): number {
    if (a.length !== b.length) throw new Error('kendallTauB: lengths differ');
    let concordant = 0;
    let discordant = 0;
    let tiesA = 0;
    let tiesB = 0;
    for (let i = 0; i < a.length; i++) {
        for (let j = i + 1; j < a.length; j++) {
            const da = Math.sign((a[i] ?? 0) - (a[j] ?? 0));
            const db = Math.sign((b[i] ?? 0) - (b[j] ?? 0));
            if (da === 0 && db === 0) continue;
            if (da === 0) tiesA++;
            else if (db === 0) tiesB++;
            else if (da === db) concordant++;
            else discordant++;
        }
    }
    const n0 = concordant + discordant;
    return (concordant - discordant) / Math.sqrt((n0 + tiesA) * (n0 + tiesB));
}

/**
 * Of the officer's top k, how many are in the formula's top k. Formula ties at
 * the k-th place are broken toward agreement (an incident tied with the
 * cut-off counts as in), since a 0.1-rounded score cannot order them.
 */
export function precisionAtK(officer: readonly number[], formula: readonly number[], k: number): number {
    const n = officer.length;
    const kk = Math.min(k, n);
    if (kk === 0) return NaN;
    const order = (xs: readonly number[]) => [...xs.keys()].sort((i, j) => (xs[j] ?? 0) - (xs[i] ?? 0));
    const officerTop = new Set(order(officer).slice(0, kk));
    const cutoff = formula[order(formula)[kk - 1] ?? 0] ?? 0;
    let hits = 0;
    for (const i of officerTop) if ((formula[i] ?? 0) >= cutoff) hits++;
    return Math.min(hits, kk) / kk;
}

export interface RankedRow extends PriorityInputs {
    id: string;
    /** 1 = most urgent. */
    officerRank: number;
    /** The score in the exported key, if present. */
    keyScore?: number;
}

export interface Variant {
    name: string;
    params: FormulaParams;
}

/** The changes priority-evaluation.md names as first to try, plus the shipped formula. */
export const VARIANTS: Variant[] = [
    { name: 'shipped (age saturates at 14 d)', params: PRODUCTION },
    { name: 'age saturates at 30 d', params: { ...PRODUCTION, saturation: { ...PRODUCTION.saturation, ageDays: 30 } } },
    { name: 'age saturates at 45 d', params: { ...PRODUCTION, saturation: { ...PRODUCTION.saturation, ageDays: 45 } } },
    {
        name: 'age weight 10% (to volume)',
        params: { ...PRODUCTION, weights: { volume: 0.5, growth: 0.3, age: 0.1, spread: 0.1 } },
    },
];

export interface Evaluation {
    n: number;
    /** Rows whose key score the port reproduces exactly / within 0.1 (spread is exported to the metre). */
    port: { exact: number; within01: number; checked: number };
    shipped: { tau: number; precisionAt10: number };
    /** Each term alone, against the officer. */
    perTerm: Record<'volume' | 'growth' | 'age' | 'spread', number>;
    /** τ on two halves split by id, so a variant is judged on incidents it was not picked on. */
    variants: { name: string; tau: number; precisionAt10: number; tauHalfA: number; tauHalfB: number }[];
}

/** Stable split: the same incident always falls in the same half. */
export function half(id: string): 'A' | 'B' {
    let h = 0;
    for (const c of id) h = (h * 31 + c.charCodeAt(0)) | 0;
    return (h & 1) === 0 ? 'A' : 'B';
}

export function evaluateRanking(rows: readonly RankedRow[]): Evaluation {
    const officer = rows.map((r) => -r.officerRank);
    const keyed = rows.filter((r) => r.keyScore !== undefined);
    const port = {
        checked: keyed.length,
        exact: keyed.filter((r) => priorityScore(r) === r.keyScore).length,
        within01: keyed.filter((r) => Math.abs(priorityScore(r) - (r.keyScore ?? 0)) <= 0.1 + 1e-9).length,
    };

    const tauFor = (subset: readonly RankedRow[], p: FormulaParams) =>
        kendallTauB(
            subset.map((r) => -r.officerRank),
            subset.map((r) => priorityScore(r, p))
        );
    const halfA = rows.filter((r) => half(r.id) === 'A');
    const halfB = rows.filter((r) => half(r.id) === 'B');

    const termOf = (name: keyof ReturnType<typeof terms>) =>
        kendallTauB(
            officer,
            rows.map((r) => terms(r)[name])
        );

    const shippedScores = rows.map((r) => priorityScore(r));
    return {
        n: rows.length,
        port,
        shipped: { tau: kendallTauB(officer, shippedScores), precisionAt10: precisionAtK(officer, shippedScores, 10) },
        perTerm: { volume: termOf('volume'), growth: termOf('growth'), age: termOf('age'), spread: termOf('spread') },
        variants: VARIANTS.map((v) => {
            const scores = rows.map((r) => priorityScore(r, v.params));
            return {
                name: v.name,
                tau: kendallTauB(officer, scores),
                precisionAt10: precisionAtK(officer, scores, 10),
                tauHalfA: tauFor(halfA, v.params),
                tauHalfB: tauFor(halfB, v.params),
            };
        }),
    };
}

/** Minimal RFC 4180 parser: quoted fields, doubled quotes, CRLF. */
export function parseCsv(text: string): Record<string, string>[] {
    const records: string[][] = [];
    let field = '';
    let record: string[] = [];
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"' && text[i + 1] === '"') {
                field += '"';
                i++;
            } else if (c === '"') quoted = false;
            else field += c;
        } else if (c === '"') quoted = true;
        else if (c === ',') {
            record.push(field);
            field = '';
        } else if (c === '\n' || c === '\r') {
            if (c === '\r' && text[i + 1] === '\n') i++;
            record.push(field);
            field = '';
            if (record.some((f) => f !== '')) records.push(record);
            record = [];
        } else field += c;
    }
    record.push(field);
    if (record.some((f) => f !== '')) records.push(record);
    const [header, ...body] = records;
    if (!header) return [];
    return body.map((r) => Object.fromEntries(header.map((h, i) => [h.trim(), (r[i] ?? '').trim()])));
}
