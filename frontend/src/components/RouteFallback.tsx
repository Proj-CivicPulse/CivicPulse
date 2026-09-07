import Skeleton from './ui/Skeleton';
import styles from './RouteFallback.module.css';

/**
 * Suspense fallback while a route chunk loads. Outlines rather than the old
 * bare "Loading…" text, so the shell does not visibly collapse and re-expand
 * between routes.
 */
export default function RouteFallback() {
    return (
        <div className={styles.wrap}>
            <Skeleton variant="text" label="Loading page" />
            <Skeleton count={3} variant="card" label="" />
        </div>
    );
}
