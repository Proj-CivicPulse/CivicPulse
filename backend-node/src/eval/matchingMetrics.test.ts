import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
    clusterConfusion,
    mixedClusters,
    pairConfusion,
    percentile,
    score,
    seededShuffle,
    simulateClustering,
    type LabelledComplaint,
} from './matchingMetrics.ts';

const complaints: LabelledComplaint[] = [
    { id: 'a', problemId: 'P', text: '' },
    { id: 'b', problemId: 'P', text: '' },
    { id: 'c', problemId: 'Q', text: '' },
];

describe('matching evaluation metrics', () => {
    it('scores precision, recall, F1 and the recall-weighted F2', () => {
        const s = score({ tp: 8, fp: 2, fn: 2, tn: 88 });
        assert.equal(s.precision, 0.8);
        assert.equal(s.recall, 0.8);
        assert.ok(Math.abs(s.f1 - 0.8) < 1e-12);
        assert.ok(Math.abs(s.f2 - 0.8) < 1e-12);
        // Recall matters more in F2: same F1, higher recall wins.
        assert.ok(score({ tp: 9, fp: 3, fn: 1, tn: 0 }).f2 > score({ tp: 7, fp: 1, fn: 3, tn: 0 }).f2);
    });

    it('judges pairs by similarity alone', () => {
        const sim = [
            [1, 0.9, 0.8],
            [0.9, 1, 0.5],
            [0.8, 0.5, 1],
        ];
        // At 0.75: a-b correct together, a-c wrongly together, b-c correctly apart.
        assert.deepEqual(pairConfusion(complaints, sim, 0.75), { tp: 1, fp: 1, fn: 0, tn: 1 });
        assert.deepEqual(pairConfusion(complaints, sim, 0.85), { tp: 1, fp: 0, fn: 0, tn: 2 });
    });

    it('reproduces single-linkage chaining, and the diameter floor stops it', () => {
        // a~b 0.8, b~c 0.8, a~c 0.2: c chains into a's incident via b.
        const sim = [
            [1, 0.8, 0.2],
            [0.8, 1, 0.8],
            [0.2, 0.8, 1],
        ];
        const chained = simulateClustering([0, 1, 2], sim, 0.75);
        assert.deepEqual(chained, [0, 0, 0]);
        // The chained pair a-c counts as together even though 0.2 < threshold.
        assert.deepEqual(clusterConfusion(complaints, chained), { tp: 1, fp: 2, fn: 0, tn: 0 });
        assert.equal(mixedClusters(complaints, chained), 1);

        const floored = simulateClustering([0, 1, 2], sim, 0.75, 0.55);
        assert.deepEqual(floored, [0, 0, 1]);
        assert.equal(mixedClusters(complaints, floored), 0);
    });

    it('shuffles deterministically and covers every index', () => {
        assert.deepEqual(seededShuffle(10, 7), seededShuffle(10, 7));
        assert.deepEqual([...seededShuffle(10, 7)].sort((a, b) => a - b), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
        assert.notDeepEqual(seededShuffle(10, 7), seededShuffle(10, 8));
    });

    it('takes nearest-rank percentiles', () => {
        assert.equal(percentile([5, 1, 3, 2, 4], 50), 3);
        assert.equal(percentile([5, 1, 3, 2, 4], 100), 5);
        assert.equal(percentile([5, 1, 3, 2, 4], 0), 1);
    });
});
