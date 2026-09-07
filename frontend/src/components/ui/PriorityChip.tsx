import { priorityLabel, type PriorityBand } from '@/lib/priority';
import { formatScore } from '@/lib/format';
import styles from './PriorityChip.module.css';

interface Props {
    band: PriorityBand;
    /** When given, renders alongside the label in tabular figures. */
    score?: number;
    className?: string;
}

/**
 * Priority is ORDINAL, so the chip carries the band name rather than relying on
 * colour alone — colour is a second channel here, never the only one.
 */
export default function PriorityChip({ band, score, className }: Props) {
    const classes = [styles.chip, styles[band], className ?? ''].filter(Boolean).join(' ');
    return (
        <span className={classes}>
            {priorityLabel(band)}
            {score !== undefined && <span className={styles.score}>{formatScore(score)}</span>}
        </span>
    );
}
