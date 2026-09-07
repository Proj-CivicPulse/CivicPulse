import styles from './HowItWorks.module.css';

const STEPS = [
    'A resident reports a problem — what it is, where it is, and a photo.',
    'CivicPulse matches it against related reports and groups them into one incident.',
    'Your ward officer sees it ranked against everything else in the ward, with the reasons for that ranking spelled out.',
];

/**
 * Numbered markers are legitimate here: this is an actual sequence. They are
 * not used anywhere in the app that is merely a list.
 */
export default function HowItWorks() {
    return (
        <section className={styles.section} aria-labelledby="how-it-works">
            <div className={styles.inner}>
                <h2 className={styles.title} id="how-it-works">
                    How it works
                </h2>
                <ol className={styles.steps}>
                    {STEPS.map((text, i) => (
                        <li key={text} className={styles.step}>
                            <span className={styles.num} aria-hidden="true">
                                {i + 1}
                            </span>
                            <p className={styles.body}>{text}</p>
                        </li>
                    ))}
                </ol>
            </div>
        </section>
    );
}
