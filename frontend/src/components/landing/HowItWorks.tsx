import styles from './HowItWorks.module.css';

const STEPS = [
    'A citizen submits a complaint — category, location, description, and a photo.',
    'CivicPulse matches it against related reports and groups them into a single incident.',
    'Officers see prioritized incidents, each with the reasons behind its score, and act on what matters most.',
];

export default function HowItWorks() {
    return (
        <section className={styles.section}>
            <div className={styles.inner}>
                <h2 className={styles.title}>How it works</h2>
                <ol className={styles.steps}>
                    {STEPS.map((text, i) => (
                        <li key={i} className={styles.step}>
                            <span className={styles.num}>{i + 1}</span>
                            <p className={styles.body}>{text}</p>
                        </li>
                    ))}
                </ol>
            </div>
        </section>
    );
}
