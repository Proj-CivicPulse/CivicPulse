import { Link } from 'react-router-dom';
import { APP_NAME } from '@/config/constants';
import styles from './TopBar.module.css';

export default function TopBar() {
    return (
        <header className={styles.topbar}>
            <div className={styles.inner}>
                <Link to="/" className={styles.wordmark}>
                    {APP_NAME}
                </Link>
                <Link to="/login" className={styles.link}>
                    Officer Login
                </Link>
            </div>
        </header>
    );
}
