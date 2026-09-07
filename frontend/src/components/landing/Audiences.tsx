import { FileText, ListChecks } from 'lucide-react';
import Button from '@/components/ui/Button';
import styles from './Audiences.module.css';

/**
 * Resident-facing only.
 *
 * This used to carry a second "For ward officers" card advertising the console.
 * It was removed on purpose: officers reach their own view by signing in with an
 * ordinary account whose role the backend assigns, so the public site has no
 * reason to advertise a separate door — and advertising one invites people to
 * try it.
 */
export default function Audiences() {
    return (
        <section className={styles.section} aria-labelledby="audiences">
            <div className={styles.inner}>
                <h2 className={styles.title} id="audiences">
                    What you can do
                </h2>

                <div className={styles.grid}>
                    <div className={styles.card}>
                        <FileText className={styles.icon} size={20} strokeWidth={1.75} aria-hidden="true" />
                        <h3 className={styles.cardTitle}>Report a problem</h3>
                        <p className={styles.cardBody}>
                            Tell us what is wrong and where. Drop a pin on the map and we work out
                            the ward ourselves. It takes about a minute and needs no account.
                        </p>
                        <div className={styles.actions}>
                            <Button to="/complaints/new" variant="primary">
                                Report a problem
                            </Button>
                        </div>
                    </div>

                    <div className={styles.card}>
                        <ListChecks className={styles.icon} size={20} strokeWidth={1.75} aria-hidden="true" />
                        <h3 className={styles.cardTitle}>Follow what you filed</h3>
                        <p className={styles.cardBody}>
                            Create an account and every report you send keeps its reference number,
                            its status, and how many other people reported the same thing.
                        </p>
                        <div className={styles.actions}>
                            <Button to="/complaints" variant="secondary">
                                Track my reports
                            </Button>
                        </div>
                    </div>
                </div>
            </div>
        </section>
    );
}
