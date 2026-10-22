import { TriangleAlert } from 'lucide-react';
import type { Incident } from '@/services/incident.service';
import styles from './HazardChip.module.css';

interface Props {
    incident: Pick<Incident, 'lifeSafety' | 'lifeSafetySource'>;
}

/**
 * The life-safety flag as a chip: icon and words, never colour alone. A flag
 * the reports suggested and no officer has decided reads "suggested".
 * Renders nothing when there is no flag.
 */
export default function HazardChip({ incident }: Props) {
    if (!incident.lifeSafety) return null;
    const suggested = incident.lifeSafetySource === 'report_text';
    return (
        <span className={`${styles.chip} ${suggested ? styles.suggested : ''}`}>
            <TriangleAlert size={12} strokeWidth={2.25} aria-hidden="true" />
            {suggested ? 'Life safety?' : 'Life safety'}
            {suggested && <span className="srOnly"> (suggested by the reports, not yet confirmed)</span>}
        </span>
    );
}
