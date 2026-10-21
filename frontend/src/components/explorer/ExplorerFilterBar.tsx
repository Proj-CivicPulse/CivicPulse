import type { NavItem } from '@/components/AppHeader';
import { WINDOW_OPTIONS } from '@/lib/reportWindow';
import { useCategoryRegistry, useWards } from '@/lib/useRegistry';
import { useDashboardStore } from '@/stores/dashboard.store';
import styles from './ExplorerFilterBar.module.css';

/**
 * The officer Explorer's sections. One shell, three views of the same filter
 * state — not three dashboards.
 */
export const EXPLORER_NAV: readonly NavItem[] = [
    { label: 'Incidents', to: '/dashboard', end: true },
    { label: 'Wards', to: '/dashboard/wards' },
    { label: 'Categories', to: '/dashboard/categories' },
];

interface Props {
    /** Show the category filter — for views whose endpoint accepts it. */
    category?: boolean;
    /** Show the ward filter — for views whose endpoint accepts it. */
    ward?: boolean;
}

/**
 * One row, above everything it scopes. The window comes first because it is
 * the filter every reader reaches for; the dimension filters follow. All of it
 * is the shared store, so the slice survives moving between views.
 *
 * Native selects for ward and category: 243 wards is a list to type into, not
 * a wall of chips, and the browser's own control is the accessible one.
 */
export default function ExplorerFilterBar({ category = false, ward = false }: Props) {
    const windowDays = useDashboardStore((s) => s.windowDays);
    const setWindowDays = useDashboardStore((s) => s.setWindowDays);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const setCategoryFilter = useDashboardStore((s) => s.setCategoryFilter);
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const selectWard = useDashboardStore((s) => s.selectWard);

    const { categories } = useCategoryRegistry();
    const wardsQuery = useWards();

    return (
        <div className={styles.bar} role="group" aria-label="Filters">
            <div className={styles.group}>
                <span className={styles.label} id="filter-window">
                    Reported
                </span>
                <div className={styles.segments} role="radiogroup" aria-labelledby="filter-window">
                    {WINDOW_OPTIONS.map((option) => (
                        <button
                            key={option.label}
                            type="button"
                            role="radio"
                            aria-checked={windowDays === option.days}
                            className={windowDays === option.days ? styles.segmentActive : styles.segment}
                            onClick={() => setWindowDays(option.days)}
                        >
                            {option.label}
                        </button>
                    ))}
                </div>
            </div>

            {category && (
                <label className={styles.group}>
                    <span className={styles.label}>Category</span>
                    <select
                        className={styles.select}
                        value={categoryFilter ?? ''}
                        onChange={(e) => setCategoryFilter(e.target.value === '' ? null : e.target.value)}
                    >
                        <option value="">All categories</option>
                        {categories.map((c) => (
                            <option key={c.code} value={c.code}>
                                {c.name}
                            </option>
                        ))}
                    </select>
                </label>
            )}

            {ward && (
                <label className={styles.group}>
                    <span className={styles.label}>Ward</span>
                    <select
                        className={styles.select}
                        value={selectedWardId ?? ''}
                        onChange={(e) => selectWard(e.target.value === '' ? null : e.target.value)}
                    >
                        <option value="">All wards</option>
                        {(wardsQuery.data ?? []).map((w) => (
                            <option key={w.id} value={w.id}>
                                {w.code} · {w.name}
                            </option>
                        ))}
                    </select>
                </label>
            )}
        </div>
    );
}
