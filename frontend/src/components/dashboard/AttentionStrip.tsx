import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { explorerService, type AttentionKey, type AttentionSignal } from '@/services/explorer.service';
import { queryKeys } from '@/lib/queryKeys';
import { formatCount } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import { useOpenIncident } from '@/lib/useOpenIncident';
import PriorityChip from '@/components/ui/PriorityChip';
import { placeName } from '@/components/explorer/IncidentList';
import styles from './AttentionStrip.module.css';

const TITLES: Record<AttentionKey, (n: number) => string> = {
    high_priority: (n) => (n === 1 ? 'high-priority incident' : 'high-priority incidents'),
    worsening: () => 'getting worse',
    in_hotspot: (n) => (n === 1 ? 'in a hotspot' : 'in hotspots'),
    category_spike: (n) => (n === 1 ? 'category spiking' : 'categories spiking'),
};

interface Props {
    wardId: string | null;
    category: string | null;
    categoryLabel: (code: string) => string;
}

/**
 * "Needs attention" (Phase 10): four statements above the queue, each opening
 * the incidents behind it. Narrowed by the queue's ward and category; each
 * signal keeps its own time window, stated in its definition.
 */
export default function AttentionStrip({ wardId, category, categoryLabel }: Props) {
    const [open, setOpen] = useState<AttentionKey | null>(null);
    const openIncident = useOpenIncident();

    const filters = useMemo(
        () => ({
            ...(wardId !== null ? { wardIds: [wardId] } : {}),
            ...(category !== null ? { categories: [category] } : {}),
        }),
        [wardId, category]
    );
    const query = useQuery({
        queryKey: queryKeys.dashboard.attention(filters),
        queryFn: () => explorerService.attention(filters),
        staleTime: 60_000,
    });

    // Quiet on failure: the queue below is the primary surface, and an error
    // card here would push it down for no action the officer can take.
    if (!query.data) return null;

    const signals = query.data.signals;
    const expanded: AttentionSignal | undefined = signals.find((s) => s.key === open);

    return (
        <section className={styles.strip} aria-label="Needs attention">
            <div className={styles.cards}>
                {signals.map((s) => {
                    // A spike is a statement about categories; its incidents are the detail.
                    const n = s.key === 'category_spike' ? s.categories.length : s.incidents.length;
                    const isOpen = open === s.key;
                    return (
                        <button
                            key={s.key}
                            type="button"
                            className={`${styles.card} ${isOpen ? styles.cardOpen : ''} ${n === 0 ? styles.cardQuiet : ''}`}
                            aria-expanded={isOpen}
                            aria-controls="attention-list"
                            disabled={n === 0}
                            onClick={() => setOpen(isOpen ? null : s.key)}
                            title={s.definition}
                        >
                            <span className={styles.count}>{formatCount(n)}</span>
                            <span className={styles.label}>{TITLES[s.key](n)}</span>
                        </button>
                    );
                })}
            </div>

            {expanded && (
                <div id="attention-list" className={styles.list}>
                    <p className={styles.definition}>{expanded.definition}.</p>
                    {expanded.categories.length > 0 && (
                        <p className={styles.definition}>
                            {expanded.categories
                                .map(
                                    (c) =>
                                        `${categoryLabel(c.category)}: ${formatCount(c.lastWeek)} this week, ` +
                                        `usually ${c.priorWeeklyMean.toFixed(1)} a week`
                                )
                                .join(' · ')}
                        </p>
                    )}
                    <ul className={styles.items}>
                        {expanded.incidents.map((i) => (
                            <li key={i.id}>
                                <button type="button" className={styles.item} onClick={() => openIncident(i.id, i.wardId)}>
                                    <span className={styles.itemName}>
                                        {categoryLabel(i.category)}
                                        {placeName(i.address) ? ` · ${placeName(i.address)}` : ''}
                                    </span>
                                    <span className={styles.itemWard}>{i.wardName}</span>
                                    <PriorityChip band={i.priorityBand} score={i.priorityScore} />
                                    <span className={styles.itemMeta}>
                                        {expanded.key === 'worsening'
                                            ? `${formatCount(i.recentReports)} new in 72 h`
                                            : `${formatCount(i.complaintCount)} reports`}
                                        {i.lastReportedAt ? ` · ${formatRelative(i.lastReportedAt)}` : ''}
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </section>
    );
}
