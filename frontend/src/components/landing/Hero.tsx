import { APP_NAME } from '@/config/constants';
import CTAButton from './CTAButton';
import styles from './Hero.module.css';

/**
 * Restrained inline motif: a location pin over a light grid with a "pulse"
 * line. Stroked with currentColor so it follows the page accent and theme.
 */
function HeroArt() {
    return (
        <svg
            className={styles.art}
            viewBox="0 0 220 180"
            fill="none"
            stroke="currentColor"
            aria-hidden="true"
        >
            <g opacity="0.25" strokeWidth="1">
                <path d="M10 40h200M10 80h200M10 120h200M10 160h200" />
                <path d="M40 20v150M90 20v150M140 20v150M190 20v150" />
            </g>
            <path
                d="M110 30c-19 0-34 15-34 34 0 25 34 56 34 56s34-31 34-56c0-19-15-34-34-34Z"
                strokeWidth="2.5"
                strokeLinejoin="round"
            />
            <circle cx="110" cy="64" r="12" strokeWidth="2.5" />
            <path
                d="M12 150h44l10-22 14 40 12-56 12 34 10-14h72"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                opacity="0.7"
            />
        </svg>
    );
}

export default function Hero() {
    return (
        <section className={styles.hero}>
            <div className={styles.inner}>
                <div className={styles.copy}>
                    <h1 className={styles.title}>
                        Turn scattered citizen complaints into incidents cities can act on.
                    </h1>
                    <p className={styles.subtitle}>
                        {APP_NAME} groups related reports into single incidents using semantic
                        matching, then gives officers a transparent, explainable priority for
                        each one.
                    </p>
                    <div className={styles.ctaRow}>
                        <CTAButton to="/submit-complaint" variant="primary">
                            Submit a Complaint
                        </CTAButton>
                        <CTAButton to="/login" variant="secondary">
                            Officer Login
                        </CTAButton>
                    </div>
                </div>
                <HeroArt />
            </div>
        </section>
    );
}
