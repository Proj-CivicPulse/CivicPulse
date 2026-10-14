import type { Ward } from '@/services/ward.service';
import type { IncidentStatus } from '@/services/incident.service';
import { PRIORITY_BAND_ORDER, priorityLabel, type PriorityBand } from '@/lib/priority';
import { humanizeEnum } from '@/lib/format';
import { WINDOW_OPTIONS } from '@/lib/reportWindow';
import { useDashboardStore } from '@/stores/dashboard.store';
import styles from './WardRail.module.css';

const STATUSES: readonly IncidentStatus[] = ['open', 'in_progress', 'resolved', 'closed'];

interface Props {
    wards: readonly Ward[];
    /** Canonical codes present in the loaded incidents — the facets worth offering. */
    categories: readonly string[];
    /** Code -> display name, resolved against the category registry. */
    categoryLabel: (code: string) => string;
}

export default function WardRail({ wards, categories, categoryLabel }: Props) {
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const statusFilter = useDashboardStore((s) => s.statusFilter);
    const bandFilter = useDashboardStore((s) => s.bandFilter);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const selectWard = useDashboardStore((s) => s.selectWard);
    const setStatusFilter = useDashboardStore((s) => s.setStatusFilter);
    const setBandFilter = useDashboardStore((s) => s.setBandFilter);
    const setCategoryFilter = useDashboardStore((s) => s.setCategoryFilter);
    const clearFilters = useDashboardStore((s) => s.clearFilters);
    const windowDays = useDashboardStore((s) => s.windowDays);
    const setWindowDays = useDashboardStore((s) => s.setWindowDays);

    const hasFilters =
        statusFilter !== null || bandFilter !== null || categoryFilter !== null || windowDays !== null;

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

            {/* Shared with every Explorer view: incidents with a report in the window. */}
            <section className={styles.group} aria-labelledby="rail-window">
                <h2 className={styles.groupTitle} id="rail-window">
                    Reported
                </h2>
                <div className={styles.chips}>
                    {WINDOW_OPTIONS.map((option) => (
                        <button
                            key={option.label}
                            type="button"
                            className={windowDays === option.days ? styles.chipActive : styles.chip}
                            aria-pressed={windowDays === option.days}
                            onClick={() => setWindowDays(option.days)}
                        >
                            {option.label}
                        </button>
                    ))}
                </div>
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
                                {categoryLabel(category)}
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
