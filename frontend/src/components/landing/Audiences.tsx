import { Link } from 'react-router-dom';
import CTAButton from './CTAButton';
import styles from './Audiences.module.css';

export default function Audiences() {
    return (
        <section className={styles.section}>
            <div className={styles.inner}>
                <h2 className={styles.title}>Who it&#39;s for</h2>
                <div className={styles.grid}>
                    <div className={styles.card}>
                        <h3 className={styles.cardTitle}>For citizens</h3>
                        <p className={styles.cardBody}>
                            File a complaint about something in your area and follow its status
                            as officers work through it.
                        </p>
                        <div className={styles.actions}>
                            <CTAButton to="/submit-complaint" variant="primary">
                                Submit a Complaint
                            </CTAButton>
                            <Link to="/my-complaints" className={styles.textLink}>
                                Track my complaints
                            </Link>
                        </div>
                    </div>
                    <div className={styles.card}>
                        <h3 className={styles.cardTitle}>For officers</h3>
                        <p className={styles.cardBody}>
                            A dashboard of prioritized incidents with a map and a grounded
                            Copilot for questions about your wards.
                        </p>
                        <div className={styles.actions}>
                            <CTAButton to="/login" variant="secondary">
                                Officer Login
                            </CTAButton>
                        </div>
                    </div>
                </div>
            </div>
        </section>
    );
}
