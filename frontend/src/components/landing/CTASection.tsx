import CTAButton from './CTAButton';
import styles from './CTASection.module.css';

export default function CTASection() {
    return (
        <section className={styles.section}>
            <div className={styles.inner}>
                <div>
                    <h2 className={styles.title}>Report an issue, or sign in to the dashboard.</h2>
                    <p className={styles.body}>
                        Submitting a complaint doesn’t require an account. Officers sign in to
                        review prioritized incidents.
                    </p>
                </div>
                <div className={styles.actions}>
                    <CTAButton to="/submit-complaint" variant="primary">
                        Submit a Complaint
                    </CTAButton>
                    <CTAButton to="/login" variant="secondary">
                        Officer Login
                    </CTAButton>
                </div>
            </div>
        </section>
    );
}
