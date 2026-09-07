import { humanizeEnum } from '@/lib/format';
import type { ComplaintStatus } from '@/services/complaint.service';
import type { IncidentStatus } from '@/services/incident.service';
import styles from './StatusChip.module.css';

/** Both enums are structurally identical; a chip renders either. */
export type Status = ComplaintStatus | IncidentStatus;

interface Props {
    status: Status;
    className?: string;
}

/**
 * Status is CATEGORICAL — a lifecycle position, not a rank. It is rendered as a
 * square outlined chip so it never reads as a priority, which is ordinal and
 * uses the filled colour ramp.
 */
export default function StatusChip({ status, className }: Props) {
    const classes = [styles.chip, styles[status], className ?? ''].filter(Boolean).join(' ');
    return <span className={classes}>{humanizeEnum(status)}</span>;
}
