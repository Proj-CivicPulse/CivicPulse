import { APP_NAME } from '@/config/constants';
import styles from './SiteFooter.module.css';

export default function SiteFooter() {
    return (
        <footer className={styles.footer}>
            <div className={styles.inner}>
                <p>{APP_NAME} — a civic-complaint management platform.</p>
                <p className={styles.note}>Project MVP — under active development.</p>
            </div>
        </footer>
    );
}
