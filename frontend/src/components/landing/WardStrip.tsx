import { useQuery } from '@tanstack/react-query';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { formatCount } from '@/lib/format';
import Skeleton from '@/components/ui/Skeleton';
import styles from './WardStrip.module.css';

/**
 * The proof under the hero: live open-incident counts per ward.
 *
 * NOTE ON THE LEFT RULES — the design brief asked for these to be
 * priority-coloured. They are not, deliberately. This strip is fed by the
 * unauthenticated GET /wards/summary, which returns counts and never rows:
 * no titles, no coordinates, no categories, and no priority scores. Publishing
 * a per-ward priority colour would leak the operational model to anyone who
 * loads the landing page. The rule is --seal instead.
 *
 * If the team wants priority here, that is a deliberate decision to publish it,
 * and the endpoint's contract has to change first.
 */
export default function WardStrip() {
    const { data, isPending, isError } = useQuery({
        queryKey: queryKeys.wards.summary(),
        queryFn: () => wardService.summary(),
        // Municipal counts do not move minute to minute, and this is on the
        // busiest public page.
        staleTime: 60_000,
    });

    return (
        <section className={styles.section} aria-labelledby="ward-strip-heading">
            <div className={styles.inner}>
                <h2 className={styles.heading} id="ward-strip-heading">
                    Open incidents by ward
                </h2>

                {isPending ? (
                    <div className={styles.grid}>
                        <Skeleton count={4} variant="card" label="Loading ward counts" />
                    </div>
                ) : isError ? (
                    // A failed side-panel must not shout on the landing page.
                    // State it plainly and let the hero carry the page.
                    <p className={styles.unavailable}>Ward counts are unavailable right now.</p>
                ) : data.length === 0 ? (
                    <p className={styles.unavailable}>No wards are configured yet.</p>
                ) : (
                    <ul className={styles.grid}>
                        {data.map((ward) => (
                            <li key={ward.wardId} className={styles.card}>
                                <span className={styles.wardName}>{ward.wardName}</span>
                                <span className={styles.count}>
                                    {formatCount(ward.openIncidentCount)}
                                </span>
                                <span className={styles.label}>
                                    {ward.openIncidentCount === 1 ? 'open incident' : 'open incidents'}
                                </span>
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </section>
    );
}
