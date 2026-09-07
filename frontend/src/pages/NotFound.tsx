import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import styles from './NotFound.module.css';

export default function NotFound() {
    return (
        <div className={styles.page}>
            <AppHeader />
            <main className={styles.main}>
                <div className={styles.column}>
                    <h1 className={styles.title}>That page doesn&rsquo;t exist.</h1>
                    <p className={styles.body}>
                        The link may be out of date, or the address may have a typo in it.
                    </p>
                    <div className={styles.actions}>
                        <Button to="/">Back to home</Button>
                        <Button to="/complaints/new" variant="quiet">
                            Report a problem
                        </Button>
                    </div>
                </div>
            </main>
        </div>
    );
}
