import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { complaintService, type Complaint } from '@/services/complaint.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatMatchingSummary, truncate } from '@/lib/format';
import { formatRelative, parseServerDate } from '@/lib/datetime';
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

type SortOrder = 'age-newest' | 'age-oldest' | 'reports-most' | 'reports-fewest';

const SORT_OPTIONS: readonly { value: SortOrder; label: string }[] = [
    { value: 'age-newest', label: 'Age · newest first' },
    { value: 'age-oldest', label: 'Age · oldest first' },
    { value: 'reports-most', label: 'Reports · most first' },
    { value: 'reports-fewest', label: 'Reports · fewest first' },
];

/** reportedAt, not createdAt: the age of the problem, not of the row. */
function reportedTime(complaint: Complaint): number {
    return parseServerDate(complaint.reportedAt)?.getTime() ?? 0;
}

function sortComplaints(complaints: readonly Complaint[], order: SortOrder): Complaint[] {
    const newestFirst = (a: Complaint, b: Complaint) => reportedTime(b) - reportedTime(a);
    // A report not yet grouped counts as zero, so it sinks under "most first"
    // rather than claiming a count the matcher has not established.
    const reports = (c: Complaint) => c.incidentComplaintCount ?? 0;

    return [...complaints].sort((a, b) => {
        switch (order) {
            case 'age-newest':
                return newestFirst(a, b);
            case 'age-oldest':
                return -newestFirst(a, b);
            case 'reports-most':
                return reports(b) - reports(a) || newestFirst(a, b);
            case 'reports-fewest':
                return reports(a) - reports(b) || newestFirst(a, b);
        }
    });
}

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

    const [sortOrder, setSortOrder] = useState<SortOrder>('age-newest');
    const complaints = useMemo(
        () => (data ? sortComplaints(data, sortOrder) : []),
        [data, sortOrder],
    );

    return (
        <div className={styles.page}>
            <AppHeader nav={NAV} />

            <main className={styles.main}>
                <div className={styles.column}>
                    <div className={styles.head}>
                        <h1 className={styles.title}>My reports</h1>

                        {complaints.length > 1 && (
                            <label className={styles.sort}>
                                <span className={styles.sortLabel}>Sort by</span>
                                <select
                                    className={styles.sortSelect}
                                    value={sortOrder}
                                    onChange={(e) => setSortOrder(e.target.value as SortOrder)}
                                >
                                    {SORT_OPTIONS.map((option) => (
                                        <option key={option.value} value={option.value}>
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        )}
                    </div>

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
                            {complaints.map((complaint) => {
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
                                                dateTime={complaint.reportedAt}
                                            >
                                                {formatRelative(complaint.reportedAt)}
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
