import styles from './WhyDifferent.module.css';

const POINTS = [
    'Semantic grouping — related complaints are matched by meaning, not by keyword or category.',
    'Every priority score comes with human-readable reasons. No black box.',
    'The Copilot answers only from CivicPulse’s own data — no invented city information.',
    'Citizens can track the status of what they filed.',
];

export default function WhyDifferent() {
    return (
        <section className={styles.section}>
            <div className={styles.inner}>
                <h2 className={styles.title}>What makes it different</h2>
                <ul className={styles.points}>
                    {POINTS.map((text, i) => (
                        <li key={i} className={styles.point}>
                            <span className={styles.mark} aria-hidden="true">
                                &#10003;
                            </span>
                            <span>{text}</span>
                        </li>
                    ))}
                </ul>
            </div>
        </section>
    );
}
