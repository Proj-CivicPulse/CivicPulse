import Button from '@/components/ui/Button';
import styles from './CTASection.module.css';

export default function CTASection() {
    return (
        <section className={styles.section}>
            <div className={styles.inner}>
                <div>
                    <h2 className={styles.title}>Seen something that needs fixing?</h2>
                    <p className={styles.body}>
                        Reporting takes about a minute and doesn&rsquo;t require an account. Create
                        one only if you want to follow what happens next.
                    </p>
                </div>
                <div className={styles.actions}>
                    <Button to="/complaints/new" variant="primary">
                        Report a problem
                    </Button>
                    <Button to="/complaints" variant="secondary">
                        Track a report
                    </Button>
                </div>
            </div>
        </section>
    );
}
