import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { incidentService, type Incident } from '@/services/incident.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount, formatCoordinates, humanizeEnum, truncate } from '@/lib/format';
import { daysSince, formatRelative } from '@/lib/datetime';
import Button from '@/components/ui/Button';
import PriorityChip from '@/components/ui/PriorityChip';
import Skeleton from '@/components/ui/Skeleton';
import StatusChip from '@/components/ui/StatusChip';
import styles from './IncidentDetail.module.css';

interface Props {
    incident: Incident;
    /** Rendered only in the drawer/sheet layouts below 1280px. */
    onClose?: () => void;
}

export default function IncidentDetail({ incident, onClose }: Props) {
    const complaintsQuery = useQuery({
        queryKey: queryKeys.incidents.complaints(incident.id),
        queryFn: () => incidentService.getComplaints(incident.id),
    });

    const age = daysSince(incident.createdAt);

    return (
        // Keyed on the incident id so the crossfade replays on every swap —
        // motion moment 2 of 3, a 100ms opacity fade and nothing else.
        <div className={styles.panel} key={incident.id}>
            <div className={styles.header}>
                <div className={styles.headerTop}>
                    <h2 className={styles.title}>
                        {incident.title ?? humanizeEnum(incident.category)}
                    </h2>
                    {onClose && (
                        <Button
                            variant="quiet"
                            size="compact"
                            onClick={onClose}
                            aria-label="Close incident details"
                        >
                            <X size={16} strokeWidth={2} aria-hidden="true" />
                        </Button>
                    )}
                </div>

                {incident.address !== null && (
                    <p className={styles.address}>{incident.address}</p>
                )}

                <div className={styles.chips}>
                    <PriorityChip band={incident.priorityBand} score={incident.priorityScore} />
                    <StatusChip status={incident.status} />
                </div>
            </div>

            {/*
             * THE HEADLINE, not a tooltip.
             *
             * Explainable prioritisation is this project's whole research claim,
             * so the justification leads the panel. When the reasons array is
             * empty the section still renders and says so — hiding it would
             * quietly turn "we can't explain this score" into "there is nothing
             * to explain".
             */}
            <section className={styles.why} aria-labelledby={`why-${incident.id}`}>
                <h3 className={styles.whyHeading} id={`why-${incident.id}`}>
                    Why this priority
                </h3>

                {incident.priorityReasons.length > 0 ? (
                    <ul className={styles.reasons}>
                        {incident.priorityReasons.map((reason) => (
                            <li key={reason} className={styles.reason}>
                                {reason}
                            </li>
                        ))}
                    </ul>
                ) : (
                    <p className={styles.noReasons}>No justification recorded.</p>
                )}
            </section>

            {incident.summary && <p className={styles.summary}>{incident.summary}</p>}

            <dl className={styles.facts}>
                <div className={styles.fact}>
                    <dt className={styles.factLabel}>Reports</dt>
                    <dd className={styles.factValue}>{formatCount(incident.complaintCount)}</dd>
                </div>
                <div className={styles.fact}>
                    <dt className={styles.factLabel}>Category</dt>
                    <dd className={styles.factValue}>{humanizeEnum(incident.category)}</dd>
                </div>
                <div className={styles.fact}>
                    <dt className={styles.factLabel}>Age</dt>
                    <dd className={styles.factValue}>{age === null ? '—' : `${age} days`}</dd>
                </div>
                {incident.lat !== null && incident.long !== null && (
                    <div className={styles.fact}>
                        <dt className={styles.factLabel}>Coordinates</dt>
                        <dd className={styles.factValue}>
                            {formatCoordinates(incident.lat, incident.long)}
                        </dd>
                    </div>
                )}
            </dl>

            {incident.aiRecommendation && (
                <section className={styles.recommendation}>
                    <h3 className={styles.whyHeading}>Suggested action</h3>
                    <p className={styles.summary}>{incident.aiRecommendation}</p>
                </section>
            )}

            <section className={styles.complaints} aria-labelledby={`reports-${incident.id}`}>
                <h3 className={styles.complaintsHeading} id={`reports-${incident.id}`}>
                    Reports
                    <span className={styles.complaintsCount}>
                        {formatCount(incident.complaintCount)}
                    </span>
                </h3>

                {complaintsQuery.isPending ? (
                    <Skeleton count={3} variant="row" label="Loading reports" />
                ) : complaintsQuery.isError ? (
                    <p className={styles.error}>
                        {describeError(complaintsQuery.error, 'reports').title}
                    </p>
                ) : complaintsQuery.data.length === 0 ? (
                    <p className={styles.noReasons}>No individual reports recorded.</p>
                ) : (
                    <ul className={styles.complaintList}>
                        {complaintsQuery.data.map((complaint) => (
                            <li key={complaint.id} className={styles.complaintRow}>
                                {/* Order matters: ref and time share row 1, the
                                    description spans row 2. */}
                                <span className={styles.complaintRef}>{complaint.referenceNo}</span>
                                <time className={styles.complaintTime} dateTime={complaint.createdAt}>
                                    {formatRelative(complaint.createdAt)}
                                </time>
                                <span className={styles.complaintText}>
                                    {truncate(complaint.description, 70)}
                                </span>
                            </li>
                        ))}
                    </ul>
                )}
            </section>
        </div>
    );
}
