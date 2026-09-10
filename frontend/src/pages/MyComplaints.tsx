import { useQuery } from '@tanstack/react-query';
import { complaintService } from '@/services/complaint.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatMatchingSummary, truncate } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import AppHeader from '@/components/AppHeader';
import Button from '@/components/ui/Button';
import EmptyState from '@/components/ui/EmptyState';
import Skeleton from '@/components/ui/Skeleton';
import StatusChip from '@/components/ui/StatusChip';
import styles from './MyComplaints.module.css';

const NAV = [
    { label: 'Report a problem', to: '/complaints/new' },
    { label: 'My reports', to: '/complaints' },
] as const;

/**
 * A stacked list, not a grid of cards. These are register entries and they read
 * top to bottom.
 */
export default function MyComplaints() {
    const { data, isPending, isError, error, refetch } = useQuery({
        queryKey: queryKeys.complaints.mine(),
        queryFn: () => complaintService.mine(),
        /*
         * "Checking for similar reports" must not sit there forever. Matching
         * settles in seconds, and a degraded grouping is repaired by the
         * reconcile sweep within about a minute, so poll only while something
         * is still in flight and stop once every report has settled.
         *
         * refetchIntervalInBackground stays at its default (false), so this
         * pauses with an unfocused tab rather than polling all day behind a
         * matcher that is genuinely down.
         */
        refetchInterval: (query) =>
            query.state.data?.some((complaint) => complaint.matchingStatus !== 'matched')
                ? 5_000
                : false,
    });

    return (
        <div className={styles.page}>
            <AppHeader nav={NAV} />

            <main className={styles.main}>
                <div className={styles.column}>
                    <h1 className={styles.title}>My reports</h1>

                    {isPending ? (
                        <div className={styles.list}>
                            <Skeleton count={4} variant="card" label="Loading your reports" />
                        </div>
                    ) : isError ? (
                        (() => {
                            const copy = describeError(error, 'reports');
                            return (
                                <EmptyState
                                    tone="error"
                                    message={copy.title}
                                    detail={copy.detail}
                                    action={
                                        <Button variant="secondary" onClick={() => void refetch()}>
                                            Try again
                                        </Button>
                                    }
                                />
                            );
                        })()
                    ) : data.length === 0 ? (
                        <EmptyState
                            message="No reports yet."
                            action={<Button to="/complaints/new">Report a problem</Button>}
                        />
                    ) : (
                        <ul className={styles.list}>
                            {data.map((complaint) => {
                                const matching = formatMatchingSummary(
                                    complaint.matchingStatus,
                                    complaint.incidentComplaintCount,
                                );

                                return (
                                    <li key={complaint.id} className={styles.row}>
                                        <div className={styles.rowMain}>
                                            <span className={styles.reference}>
                                                {complaint.referenceNo}
                                                {complaint.address !== null && (
                                                    <span className={styles.address}>
                                                        {complaint.address}
                                                    </span>
                                                )}
                                            </span>
                                            <p className={styles.description}>
                                                {truncate(complaint.description, 90)}
                                            </p>
                                            {/*
                                             * The single most important thing a
                                             * resident learns here: their report
                                             * did not vanish into a queue of one.
                                             *
                                             * Rendered fainter until matching has
                                             * settled, so a provisional grouping
                                             * never carries a final one's weight.
                                             */}
                                            {matching !== null && (
                                                <p
                                                    className={
                                                        matching.settled
                                                            ? styles.grouped
                                                            : styles.groupedUnsettled
                                                    }
                                                >
                                                    {matching.text}
                                                </p>
                                            )}
                                        </div>

                                        <div className={styles.rowMeta}>
                                            <StatusChip status={complaint.status} />
                                            <time
                                                className={styles.timestamp}
                                                dateTime={complaint.createdAt}
                                            >
                                                {formatRelative(complaint.createdAt)}
                                            </time>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    )}
                </div>
            </main>
        </div>
    );
}
