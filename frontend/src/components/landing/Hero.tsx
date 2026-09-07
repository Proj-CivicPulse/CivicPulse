import { APP_NAME } from '@/config/constants';
import Button from '@/components/ui/Button';
import styles from './Hero.module.css';

/**
 * Left-aligned, two lines, no artwork. The proof underneath is live ward data
 * (see WardStrip) rather than an illustration or a product screenshot — the
 * claim is about real complaint volume, so real numbers make it better than a
 * drawing would.
 */
export default function Hero() {
    return (
        <section className={styles.hero}>
            <div className={styles.inner}>
                <h1 className={styles.title}>
                    Report a civic problem.
                    <br />
                    We group it with everyone else&rsquo;s.
                </h1>

                <p className={styles.subtitle}>
                    One pothole is a complaint. Forty reports of the same pothole is a priority.{' '}
                    {APP_NAME} finds the pattern and tells your ward officer.
                </p>

                <div className={styles.ctaRow}>
                    <Button to="/complaints/new" variant="primary">
                        Report a problem
                    </Button>
                    <Button to="/complaints" variant="quiet">
                        Track an existing report
                    </Button>
                </div>
            </div>
        </section>
    );
}
