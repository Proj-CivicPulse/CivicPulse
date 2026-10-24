import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { incidentService, type Incident } from '@/services/incident.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount, truncate } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import { useDashboardStore } from '@/stores/dashboard.store';
import Button from '@/components/ui/Button';
import Skeleton from '@/components/ui/Skeleton';
import ReportPhoto from './ReportPhoto';
import styles from './IncidentDetail.module.css';

/**
 * The incident's reports, each with why it is here ("grouped with CP-…: a
 * very similar description, 40 m away") and, on an active incident with more
 * than one report, the officer's override: split it off as a different problem.
 */
export default function IncidentReports({ incident }: { incident: Incident }) {
    const queryClient = useQueryClient();
    const selectIncident = useDashboardStore((s) => s.selectIncident);
    const [confirming, setConfirming] = useState<string | null>(null);

    const complaintsQuery = useQuery({
        queryKey: queryKeys.incidents.complaints(incident.id),
        queryFn: () => incidentService.getComplaints(incident.id),
    });
    // Secondary: the reports render without it, and an error here says nothing to fix.
    const groupingQuery = useQuery({
        queryKey: queryKeys.incidents.grouping(incident.id),
        queryFn: () => incidentService.grouping(incident.id),
    });
    const why = new Map((groupingQuery.data?.members ?? []).map((m) => [m.complaintId, m]));

    const unlink = useMutation({
        mutationFn: (complaintId: string) => incidentService.unlink(incident.id, complaintId),
        onSuccess: () => {
            setConfirming(null);
            void queryClient.invalidateQueries({ queryKey: queryKeys.incidents.all() });
            void queryClient.invalidateQueries({ queryKey: queryKeys.dashboard.all() });
        },
    });

    const active = incident.status === 'open' || incident.status === 'in_progress';
    const canSplit = active && incident.complaintCount > 1;

    return (
        <section className={styles.complaints} aria-labelledby={`reports-${incident.id}`}>
            <h3 className={styles.complaintsHeading} id={`reports-${incident.id}`}>
                Reports
                <span className={styles.complaintsCount}>{formatCount(incident.complaintCount)}</span>
            </h3>

            {complaintsQuery.isPending ? (
                <Skeleton count={3} variant="row" label="Loading reports" />
            ) : complaintsQuery.isError ? (
                <p className={styles.error}>{describeError(complaintsQuery.error, 'reports').title}</p>
            ) : complaintsQuery.data.length === 0 ? (
                <p className={styles.noReasons}>No individual reports recorded.</p>
            ) : (
                <ul className={styles.complaintList}>
                    {complaintsQuery.data.map((complaint) => {
                        const reason = why.get(complaint.id);
                        return (
                            <li key={complaint.id} className={styles.complaintRow}>
                                {/* Order matters: ref and time share row 1, the
                                    description spans row 2. */}
                                <span className={styles.complaintRef}>{complaint.referenceNo}</span>
                                {/* reportedAt: the timeline is ordered by it, and for an
                                    imported report createdAt is only when it arrived. */}
                                <time className={styles.complaintTime} dateTime={complaint.reportedAt}>
                                    {formatRelative(complaint.reportedAt)}
                                </time>
                                <span className={styles.complaintText}>{truncate(complaint.description, 70)}</span>
                                {complaint.hasPhoto && <ReportPhoto complaintId={complaint.id} />}
                                {reason && <span className={styles.complaintWhy}>{reason.explanation}</span>}
                                {canSplit &&
                                    (confirming === complaint.id ? (
                                        <span className={styles.complaintActions}>
                                            <span className={styles.complaintWhy}>
                                                Split this report off into an incident of its own?
                                            </span>
                                            <Button
                                                size="compact"
                                                variant="secondary"
                                                disabled={unlink.isPending}
                                                onClick={() => unlink.mutate(complaint.id)}
                                            >
                                                {unlink.isPending ? 'Splitting…' : 'Split off'}
                                            </Button>
                                            <Button size="compact" variant="quiet" onClick={() => setConfirming(null)}>
                                                Cancel
                                            </Button>
                                        </span>
                                    ) : (
                                        <span className={styles.complaintActions}>
                                            <Button
                                                size="compact"
                                                variant="quiet"
                                                onClick={() => {
                                                    unlink.reset();
                                                    setConfirming(complaint.id);
                                                }}
                                            >
                                                Not the same problem…
                                            </Button>
                                        </span>
                                    ))}
                            </li>
                        );
                    })}
                </ul>
            )}

            {unlink.isError && <p className={styles.error}>{describeError(unlink.error, 'the report').title}</p>}
            {unlink.isSuccess && (
                <p className={styles.complaintWhy}>
                    Split off.{' '}
                    <Button size="compact" variant="quiet" onClick={() => selectIncident(unlink.data.target.id)}>
                        Open the new incident
                    </Button>
                </p>
            )}
        </section>
    );
}
