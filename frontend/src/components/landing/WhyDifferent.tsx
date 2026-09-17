import styles from './WhyDifferent.module.css';

/**
 * Claims about what the system does TODAY, and nothing else.
 *
 * Two were corrected after an audit found the page describing behaviour the
 * code does not have:
 *
 *  - "matched by meaning, not by keyword or category" was wrong in a way that
 *    mattered. Category is a HARD pre-filter — backend-node narrows candidates
 *    by ward + category + status before any vector maths runs — so meaning
 *    decides the match WITHIN a category, never across one.
 *  - the officer Copilot is a Phase 6 stub that returns 501. Describing how it
 *    answers questions is describing something nobody can use yet.
 *
 * Anything added here must be true of what is deployed, not of what is planned.
 */
const POINTS = [
    'Related reports are grouped by what they mean, not by matching words — within the same ward and kind of problem.',
    'Every priority score comes with human-readable reasons. No black box.',
    'When the matcher is unavailable, reports are still grouped and still visible — and regrouped once it recovers.',
    'Residents can follow the status of what they filed.',
];

/**
 * A left rule per item rather than a tick glyph. The rule is the system's own
 * structural device and reads as a register entry; a row of ticks reads as a
 * marketing feature list.
 */
export default function WhyDifferent() {
    return (
        <section className={styles.section} aria-labelledby="why-different">
            <div className={styles.inner}>
                <h2 className={styles.title} id="why-different">
                    What makes it different
                </h2>
                <ul className={styles.points}>
                    {POINTS.map((text) => (
                        <li key={text} className={styles.point}>
                            {text}
                        </li>
                    ))}
                </ul>
            </div>
        </section>
    );
}
