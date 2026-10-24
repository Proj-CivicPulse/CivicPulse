import { useMemo } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { explorerService, type CategoryExplorerSummary } from '@/services/explorer.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount } from '@/lib/format';
import { formatRelative, parseServerDate } from '@/lib/datetime';
import { formatHours } from '@/lib/duration';
import { reportWindow, trendRange } from '@/lib/reportWindow';
import { useCategoryRegistry, useDepartmentNames } from '@/lib/useRegistry';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import CopilotSection, { copilotScope } from '@/components/copilot/CopilotSection';
import ExplorerFilterBar, { EXPLORER_NAV } from '@/components/explorer/ExplorerFilterBar';
import { Sparkline, type TrendPoint } from '@/components/explorer/TrendChart';
import Button from '@/components/ui/Button';
import DataTable, { type Column } from '@/components/ui/DataTable';
import EmptyState from '@/components/ui/EmptyState';
import Panel from '@/components/ui/Panel';
import Skeleton from '@/components/ui/Skeleton';
import styles from './Explorer.module.css';

/**
 * Category Explorer: what kinds of problems are occurring, where,
 * and how are they changing?
 *
 * The comparison across categories is a table with a per-row sparkline —
 * small multiples — not one chart with eight coloured lines, which no palette
 * keeps distinguishable. Every number is the canonical-code aggregate from
 * GET /dashboard/categories; nothing is re-derived here.
 */
export default function CategoryExplorer() {
    const navigate = useNavigate();
    const windowDays = useDashboardStore((s) => s.windowDays);
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const { label: categoryLabel } = useCategoryRegistry();
    const departmentName = useDepartmentNames();

    const wardIds = useMemo(() => (selectedWardId ? [selectedWardId] : undefined), [selectedWardId]);
    const filters = useMemo(() => ({ ...reportWindow(windowDays), wardIds }), [windowDays, wardIds]);

    const summaryQuery = useQuery({
        queryKey: queryKeys.dashboard.categories(filters),
        queryFn: () => explorerService.categories(filters),
        placeholderData: keepPreviousData,
    });

    // Weekly buckets for the row sparklines: ~13 marks read; 90 daily ones blur.
    const range = useMemo(() => trendRange(windowDays), [windowDays]);
    const trendFilters = useMemo(
        () => ({ from: range.from, to: range.to, bucket: 'week' as const, groupBy: 'category' as const, wardIds }),
        [range, wardIds]
    );
    const trendQuery = useQuery({
        queryKey: queryKeys.dashboard.trends(trendFilters),
        queryFn: () => explorerService.trends(trendFilters),
        placeholderData: keepPreviousData,
    });

    const seriesByCategory = useMemo(() => {
        const map = new Map<string, readonly TrendPoint[]>();
        for (const s of trendQuery.data?.series ?? []) if (s.key) map.set(s.key, s.points);
        return map;
    }, [trendQuery.data]);
    const emptySeries = useMemo(() => {
        const first = trendQuery.data?.series[0];
        return first ? first.points.map((p) => ({ start: p.start, count: 0 })) : [];
    }, [trendQuery.data]);

    const rows = summaryQuery.data ?? [];

    const columns = useMemo<Column<CategoryExplorerSummary>[]>(
        () => [
            {
                key: 'category',
                header: 'Category',
                cell: (r) => categoryLabel(r.category),
                sortValue: (r) => categoryLabel(r.category),
            },
            {
                key: 'complaints',
                header: 'Complaints',
                cell: (r) => formatCount(r.complaintCount),
                sortValue: (r) => r.complaintCount,
                align: 'end',
                width: '100px',
            },
            {
                key: 'trend',
                header: range.label,
                cell: (r) => (
                    <Sparkline
                        points={seriesByCategory.get(r.category) ?? emptySeries}
                        label={`${categoryLabel(r.category)}, complaints per week`}
                    />
                ),
                width: '120px',
            },
            {
                key: 'active',
                header: 'Active',
                cell: (r) => formatCount(r.activeIncidentCount),
                sortValue: (r) => r.activeIncidentCount,
                align: 'end',
                width: '80px',
            },
            {
                key: 'high',
                header: 'High priority',
                cell: (r) => formatCount(r.highPriorityIncidentCount),
                sortValue: (r) => r.highPriorityIncidentCount,
                align: 'end',
                width: '110px',
            },
            {
                key: 'wards',
                header: 'Wards',
                cell: (r) => formatCount(r.wardCount),
                sortValue: (r) => r.wardCount,
                align: 'end',
                width: '80px',
            },
            {
                key: 'resolved',
                header: 'Fixed',
                cell: (r) => formatCount(r.resolvedIncidentCount),
                sortValue: (r) => r.resolvedIncidentCount,
                align: 'end',
                width: '70px',
            },
            {
                key: 'median',
                header: 'Median time to fix',
                cell: (r) => (r.medianHoursToResolve === null ? '—' : formatHours(r.medianHoursToResolve)),
                sortValue: (r) => r.medianHoursToResolve ?? Number.MAX_SAFE_INTEGER,
                align: 'end',
                width: '140px',
            },
            {
                key: 'owner',
                header: 'Default department',
                cell: (r) => departmentName(r.defaultDepartmentId) ?? 'Triage by hand',
                sortValue: (r) => departmentName(r.defaultDepartmentId) ?? '',
            },
            {
                key: 'last',
                header: 'Last report',
                cell: (r) => (r.lastActivityAt ? formatRelative(r.lastActivityAt) : '—'),
                sortValue: (r) => parseServerDate(r.lastActivityAt ?? '')?.getTime() ?? 0,
                align: 'end',
                width: '120px',
            },
        ],
        [categoryLabel, departmentName, seriesByCategory, emptySeries, range.label]
    );

    const error = summaryQuery.isError ? describeError(summaryQuery.error, 'category figures') : null;

    return (
        <div className={styles.page}>
            <AppHeader wide nav={EXPLORER_NAV} />
            <main className={styles.main}>
                <div className={styles.head}>
                    <h1 className={styles.title}>Categories</h1>
                    <ExplorerFilterBar ward />
                </div>

                <CopilotSection scope={copilotScope(selectedWardId, null, windowDays)} />

                {error ? (
                    <Panel>
                        <EmptyState
                            tone="error"
                            message={error.title}
                            detail={error.detail}
                            action={
                                <Button variant="secondary" size="compact" onClick={() => void summaryQuery.refetch()}>
                                    Try again
                                </Button>
                            }
                        />
                    </Panel>
                ) : (
                    <Panel heading="All categories" flush as="section" className={summaryQuery.isPlaceholderData ? styles.stale : styles.fresh}>
                        {summaryQuery.isPending ? (
                            <div style={{ padding: 'var(--space-4)' }}>
                                <Skeleton count={8} variant="row" label="Loading categories" />
                            </div>
                        ) : (
                            <DataTable
                                caption="Categories with complaint, incident and resolution figures, sortable"
                                columns={columns}
                                rows={rows}
                                rowKey={(r) => r.category}
                                onSelect={(r) => navigate(`/dashboard/categories/${encodeURIComponent(r.category)}`)}
                                emptyMessage="No categories to show."
                            />
                        )}
                    </Panel>
                )}

                <p className={styles.note}>
                    &ldquo;Fixed&rdquo; counts incidents marked resolved; closed incidents were not fixed and are
                    never counted. Time to fix runs from the first report to resolution, and is recorded
                    from 6 Oct 2026 onwards.
                </p>
            </main>
        </div>
    );
}
