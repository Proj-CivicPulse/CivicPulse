/* eslint-disable no-console -- a command-line report: printing is its output */
/**
 * Scores threshold and distance policies against the hand-labelled REAL joins
 * in eval/production-joins-labelled.json.
 *
 *   npm run eval:production-joins     # from backend-node/; no API calls
 *
 * Every row is a decision production already made (a join, at 0.75). For a
 * candidate policy we ask which of those joins it would have kept:
 *   wrong joins removed  — "different" decisions the policy refuses
 *   right joins lost     — "same" decisions the policy refuses
 * Ambiguous pairs are reported but excluded from both, so a policy is never
 * credited or blamed for a call a person could not make either.
 *
 * Coverage note: these are all joins below 0.90 similarity. Joins at 0.90+ are
 * not labelled here (73 of them sit at 0.95+, mostly verbatim seed reuse).
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

interface Pair {
    id: string;
    similarity: number;
    distancesM: number[];
    label: 'same' | 'different' | 'ambiguous';
}

const here = dirname(fileURLToPath(import.meta.url));
const { pairs } = JSON.parse(readFileSync(join(here, 'production-joins-labelled.json'), 'utf8')) as { pairs: Pair[] };

const decisions = pairs.flatMap((p) => p.distancesM.map((d) => ({ sim: p.similarity, distanceM: d, label: p.label })));
const total = (label: Pair['label']) => decisions.filter((d) => d.label === label).length;

console.log(`${decisions.length} labelled decisions: ${total('same')} same, ${total('different')} different, ${total('ambiguous')} ambiguous\n`);
console.log('| threshold | distance gate | wrong joins removed | right joins lost | ambiguous refused | precision of kept joins |');
console.log('|---|---|---|---|---|---|');

for (const threshold of [0.75, 0.8, 0.82, 0.84, 0.85, 0.87]) {
    for (const gate of [null, 1000, 500, 250]) {
        const keep = (d: (typeof decisions)[number]) => d.sim >= threshold && (gate === null || d.distanceM <= gate);
        const removedWrong = decisions.filter((d) => d.label === 'different' && !keep(d)).length;
        const lostRight = decisions.filter((d) => d.label === 'same' && !keep(d)).length;
        const refusedAmbiguous = decisions.filter((d) => d.label === 'ambiguous' && !keep(d)).length;
        const keptSame = decisions.filter((d) => d.label === 'same' && keep(d)).length;
        const keptDifferent = decisions.filter((d) => d.label === 'different' && keep(d)).length;
        const precision = keptSame + keptDifferent === 0 ? 1 : keptSame / (keptSame + keptDifferent);
        console.log(
            `| ${threshold.toFixed(2)} | ${gate === null ? 'none' : `≤ ${gate} m`} | ${removedWrong}/${total('different')} | ${lostRight}/${total('same')} | ${refusedAmbiguous}/${total('ambiguous')} | ${(precision * 100).toFixed(0)}% |`,
        );
    }
}

const same = decisions.filter((d) => d.label === 'same').map((d) => d.distanceM);
const different = decisions.filter((d) => d.label === 'different').map((d) => d.distanceM);
console.log(`\nDistance, same-problem joins: max ${Math.max(...same)} m`);
console.log(`Distance, different-problem joins: ${[...different].sort((a, b) => a - b).join(', ')} m`);
