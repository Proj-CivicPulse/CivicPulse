import styles from './ErrorFallback.module.css';

interface Props {
    error: Error;
    retry: () => void;
}

/**
 * Rendered by the top-level ErrorBoundary, which sits OUTSIDE BrowserRouter —
 * so no router hooks, no <Link>, and no shared AppHeader here. A plain anchor
 * is the only navigation available, and that is deliberate: if the router
 * itself is what failed, a Link would fail with it.
 */
export default function ErrorFallback({ error, retry }: Props) {
    return (
        <div className={styles.wrap} role="alert">
            <div className={styles.column}>
                <h1 className={styles.title}>This page stopped working.</h1>
                <p className={styles.body}>
                    Reloading usually clears it. If it keeps happening, the details below are
                    what a developer needs.
                </p>

                <div className={styles.actions}>
                    <button type="button" className={styles.primary} onClick={retry}>
                        Try again
                    </button>
                    <a className={styles.quiet} href="/">
                        Back to home
                    </a>
                </div>

                <pre className={styles.detail}>{error.message}</pre>
            </div>
        </div>
    );
}
