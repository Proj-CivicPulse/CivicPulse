import styles from './WhyDifferent.module.css';

const POINTS = [
    'Related reports are matched by meaning, not by keyword or category.',
    'Every priority score comes with human-readable reasons. No black box.',
    'The Copilot answers only from CivicPulse’s own records — it does not invent city information.',
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
