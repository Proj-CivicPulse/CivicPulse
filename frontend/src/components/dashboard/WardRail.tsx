import type { Ward } from '@/services/ward.service';
import type { IncidentStatus } from '@/services/incident.service';
import { PRIORITY_BAND_ORDER, priorityLabel, type PriorityBand } from '@/lib/priority';
import { humanizeEnum } from '@/lib/format';
import { useDashboardStore } from '@/stores/dashboard.store';
import styles from './WardRail.module.css';

const STATUSES: readonly IncidentStatus[] = ['open', 'in_progress', 'resolved', 'closed'];

interface Props {
    wards: readonly Ward[];
    /** Derived from the loaded incidents — there is no categories endpoint. */
    categories: readonly string[];
}

export default function WardRail({ wards, categories }: Props) {
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const statusFilter = useDashboardStore((s) => s.statusFilter);
    const bandFilter = useDashboardStore((s) => s.bandFilter);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const selectWard = useDashboardStore((s) => s.selectWard);
    const setStatusFilter = useDashboardStore((s) => s.setStatusFilter);
    const setBandFilter = useDashboardStore((s) => s.setBandFilter);
    const setCategoryFilter = useDashboardStore((s) => s.setCategoryFilter);
    const clearFilters = useDashboardStore((s) => s.clearFilters);

    const hasFilters = statusFilter !== null || bandFilter !== null || categoryFilter !== null;

    return (
        <div className={styles.rail}>
            <section className={styles.group} aria-labelledby="rail-wards">
                <h2 className={styles.groupTitle} id="rail-wards">
                    Wards
                </h2>
                <ul className={styles.list}>
                    <li>
                        <button
                            type="button"
                            className={selectedWardId === null ? styles.itemActive : styles.item}
                            aria-pressed={selectedWardId === null}
                            onClick={() => selectWard(null)}
                        >
                            All wards
                        </button>
                    </li>
                    {wards.map((ward) => (
                        <li key={ward.id}>
                            <button
                                type="button"
                                className={selectedWardId === ward.id ? styles.itemActive : styles.item}
                                aria-pressed={selectedWardId === ward.id}
                                onClick={() => selectWard(ward.id)}
                            >
                                {ward.name}
                                {ward.zone && <span className={styles.zone}>{ward.zone}</span>}
                            </button>
                        </li>
                    ))}
                </ul>
            </section>

            <section className={styles.group} aria-labelledby="rail-priority">
                <h2 className={styles.groupTitle} id="rail-priority">
                    Priority
                </h2>
                <div className={styles.chips}>
                    {PRIORITY_BAND_ORDER.map((band: PriorityBand) => (
                        <button
                            key={band}
                            type="button"
                            className={bandFilter === band ? styles.chipActive : styles.chip}
                            aria-pressed={bandFilter === band}
                            onClick={() => setBandFilter(bandFilter === band ? null : band)}
                        >
                            {priorityLabel(band)}
                        </button>
                    ))}
                </div>
            </section>

            <section className={styles.group} aria-labelledby="rail-status">
                <h2 className={styles.groupTitle} id="rail-status">
                    Status
                </h2>
                <div className={styles.chips}>
                    {STATUSES.map((status) => (
                        <button
                            key={status}
                            type="button"
                            className={statusFilter === status ? styles.chipActive : styles.chip}
                            aria-pressed={statusFilter === status}
                            onClick={() => setStatusFilter(statusFilter === status ? null : status)}
                        >
                            {humanizeEnum(status)}
                        </button>
                    ))}
                </div>
            </section>

            {categories.length > 0 && (
                <section className={styles.group} aria-labelledby="rail-category">
                    <h2 className={styles.groupTitle} id="rail-category">
                        Category
                    </h2>
                    <div className={styles.chips}>
                        {categories.map((category) => (
                            <button
                                key={category}
                                type="button"
                                className={categoryFilter === category ? styles.chipActive : styles.chip}
                                aria-pressed={categoryFilter === category}
                                onClick={() =>
                                    setCategoryFilter(categoryFilter === category ? null : category)
                                }
                            >
                                {humanizeEnum(category)}
                            </button>
                        ))}
                    </div>
                </section>
            )}

            {hasFilters && (
                <button type="button" className={styles.clear} onClick={clearFilters}>
                    Clear filters
                </button>
            )}
        </div>
    );
}
