import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
    evaluateRanking,
    kendallTauB,
    parseCsv,
    precisionAtK,
    priorityScore,
    PRODUCTION,
    terms,
    type RankedRow,
} from './priorityMetrics.ts';

describe('priority formula port', () => {
    it('reproduces PriorityService on hand-checked cases and the calibration probes', () => {
        // Same cases as backend-spring RankingSheetServiceTest.
        assert.equal(priorityScore({ reports: 1, reportsLast24h: 0, daysOpen: 20, spreadM: 0 }), 2.9);
        assert.equal(priorityScore({ reports: 3, reportsLast24h: 2, daysOpen: 2, spreadM: 0 }), 3.2);
        // docs/priority-evaluation.md, "Operational plausibility probes".
        assert.equal(priorityScore({ reports: 40, reportsLast24h: 20, daysOpen: 1, spreadM: 300 }), 7.4);
        assert.equal(priorityScore({ reports: 40, reportsLast24h: 0, daysOpen: 30, spreadM: 300 }), 6.3);
        assert.equal(priorityScore({ reports: 1, reportsLast24h: 0, daysOpen: 60, spreadM: 0 }), 2.9);
    });

    it('saturates every term at 1 and never goes negative', () => {
        const t = terms({ reports: 1000, reportsLast24h: 99, daysOpen: 999, spreadM: 99_999 }, PRODUCTION);
        assert.deepEqual(t, { volume: 1, growth: 1, age: 1, spread: 1 });
        assert.equal(priorityScore({ reports: 1000, reportsLast24h: 99, daysOpen: 999, spreadM: 99_999 }), 10);
        assert.equal(terms({ reports: 0, reportsLast24h: -1, daysOpen: 0, spreadM: 0 }).growth, 0);
    });
});

describe('ranking agreement metrics', () => {
    it('Kendall τ-b is 1 for the same order, −1 for the reverse, and corrects for ties', () => {
        assert.equal(kendallTauB([1, 2, 3, 4], [10, 20, 30, 40]), 1);
        assert.equal(kendallTauB([1, 2, 3, 4], [40, 30, 20, 10]), -1);
        // 5 concordant, 0 discordant, 1 tie in b: 5 / sqrt(6 * 5).
        assert.ok(Math.abs(kendallTauB([1, 2, 3, 4], [1, 2, 3, 3]) - 5 / Math.sqrt(30)) < 1e-12);
        assert.ok(Number.isNaN(kendallTauB([1, 1, 1], [1, 2, 3])));
    });

    it('precision@k counts the officer top k found in the formula top k, ties at the cut-off counting in', () => {
        const officer = [5, 4, 3, 2, 1];
        assert.equal(precisionAtK(officer, [50, 40, 30, 20, 10], 2), 1);
        assert.equal(precisionAtK(officer, [10, 20, 30, 40, 50], 2), 0);
        assert.equal(precisionAtK(officer, [50, 30, 30, 20, 10], 2), 1);   // tie at the cut-off
        assert.equal(precisionAtK(officer, [50, 40, 30, 20, 10], 10), 1);  // k capped at n
    });
});

describe('evaluateRanking on a fake officer session', () => {
    // The fake officer cares about volume and recency, not age: they rank by
    // reports in the last 24 h, then by reports.
    const rows: RankedRow[] = Array.from({ length: 30 }, (_, i) => ({
        id: String(100 + i),
        reports: 1 + ((i * 7) % 15),
        reportsLast24h: (i * 3) % 5,
        daysOpen: (i * 11) % 40,
        spreadM: (i * 37) % 900,
        officerRank: 0,
    }));
    rows.sort((a, b) => b.reportsLast24h - a.reportsLast24h || b.reports - a.reports || a.id.localeCompare(b.id));
    rows.forEach((r, i) => {
        r.officerRank = i + 1;
        r.keyScore = priorityScore(r);
    });

    it('checks the port against the key, and scores the shipped formula and each variant', () => {
        const ev = evaluateRanking(rows);
        assert.equal(ev.n, 30);
        assert.deepEqual(ev.port, { checked: 30, exact: 30, within01: 30 });
        assert.ok(ev.shipped.tau > 0.3, `formula should broadly agree, got ${ev.shipped.tau}`);
        assert.ok(ev.perTerm.growth > ev.perTerm.age, 'this officer weighs recency over age');
        assert.equal(ev.variants.length, 4);
        assert.ok(ev.variants.every((v) => Number.isFinite(v.tauHalfA) && Number.isFinite(v.tauHalfB)));
    });

    it('notices a key the port does not reproduce', () => {
        const drifted = rows.map((r, i) => (i === 0 ? { ...r, keyScore: (r.keyScore ?? 0) + 1 } : r));
        assert.equal(evaluateRanking(drifted).port.exact, 29);
    });
});

describe('parseCsv', () => {
    it('handles quotes, doubled quotes, commas in fields and CRLF', () => {
        const rows = parseCsv('a,b,c\r\n1,"x, ""y""",3\r\n\r\n4,,6\n');
        assert.deepEqual(rows, [
            { a: '1', b: 'x, "y"', c: '3' },
            { a: '4', b: '', c: '6' },
        ]);
    });
});
