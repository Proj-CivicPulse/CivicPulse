import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { explorerService } from '@/services/explorer.service';
import { incidentService } from '@/services/incident.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import { formatHours } from '@/lib/duration';
import { reportWindow, trendRange } from '@/lib/reportWindow';
import { useCategoryRegistry, useDepartmentNames } from '@/lib/useRegistry';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import ExplorerFilterBar, { EXPLORER_NAV } from '@/components/explorer/ExplorerFilterBar';
import { DistributionBars, StatTiles } from '@/components/explorer/Figures';
import IncidentList from '@/components/explorer/IncidentList';
import TrendChart from '@/components/explorer/TrendChart';
import Button from '@/components/ui/Button';
import EmptyState from '@/components/ui/EmptyState';
import Panel from '@/components/ui/Panel';
import Skeleton from '@/components/ui/Skeleton';
import styles from './Explorer.module.css';

const TOP_WARDS = 10;

/**
 * Category detail: trend → incident count → ward distribution →
 * recent incidents. Display name and default department come from the server;
 * no category semantics live in this file.
 */
export default function CategoryDetail() {
    const { code = '' } = useParams();
    const windowDays = useDashboardStore((s) => s.windowDays);
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const { label: categoryLabel, isPending: registryPending } = useCategoryRegistry();
    const departmentName = useDepartmentNames();
    const [trendView, setTrendView] = useState<'chart' | 'table'>('chart');

    const reported = useMemo(() => reportWindow(windowDays), [windowDays]);
    const wardIds = useMemo(() => (selectedWardId ? [selectedWardId] : undefined), [selectedWardId]);
    const range = useMemo(() => trendRange(windowDays), [windowDays]);

    const summaryFilters = useMemo(() => ({ ...reported, wardIds }), [reported, wardIds]);
    const summaryQuery = useQuery({
        queryKey: queryKeys.dashboard.categories(summaryFilters),
        queryFn: () => explorerService.categories(summaryFilters),
        placeholderData: keepPreviousData,
    });

    const trendFilters = useMemo(
        () => ({ from: range.from, to: range.to, bucket: range.bucket, wardIds, categories: [code] }),
        [range, wardIds, code]
    );
    const trendQuery = useQuery({
        queryKey: queryKeys.dashboard.trends(trendFilters),
        queryFn: () => explorerService.trends(trendFilters),
        placeholderData: keepPreviousData,
    });

    // Ward distribution: the ward endpoint filtered to this category.
    const wardFilters = useMemo(() => ({ ...reported, categories: [code] }), [reported, code]);
    const wardsQuery = useQuery({
        queryKey: queryKeys.dashboard.wards(wardFilters),
        queryFn: () => explorerService.wards(wardFilters),
        placeholderData: keepPreviousData,
    });

    const incidentFilters = useMemo(
        () => ({
            ...reported,
            wardIds,
            categories: [code],
            statuses: ['open' as const, 'in_progress' as const],
            limit: 10,
        }),
        [reported, wardIds, code]
    );
    const incidentsQuery = useQuery({
        queryKey: queryKeys.incidents.list(incidentFilters),
        queryFn: () => incidentService.list(incidentFilters),
        placeholderData: keepPreviousData,
    });

    const row = summaryQuery.data?.find((r) => r.category === code);
    const topWards = useMemo(
        () =>
            (wardsQuery.data ?? [])
                .filter((w) => w.complaintCount > 0)
                .sort((a, b) => b.complaintCount - a.complaintCount)
                .slice(0, TOP_WARDS),
        [wardsQuery.data]
    );

    const name = categoryLabel(code);

    if (summaryQuery.isError) {
        const err = describeError(summaryQuery.error, 'this category');
        return (
            <div className={styles.page}>
                <AppHeader wide nav={EXPLORER_NAV} />
                <main className={styles.main}>
                    <Panel>
                        <EmptyState tone="error" message={err.title} detail={err.detail} />
                    </Panel>
                </main>
            </div>
        );
    }

    const unknown = summaryQuery.isSuccess && !registryPending && row === undefined;

    return (
        <div className={styles.page}>
            <AppHeader wide nav={EXPLORER_NAV} />
            <main className={styles.main}>
                <div className={styles.head}>
                    <p className={styles.crumb}>
                        <Link to="/dashboard/categories">Categories</Link> / {name}
                    </p>
                    <h1 className={styles.title}>{name}</h1>
                    <ExplorerFilterBar ward />
                </div>

                {unknown ? (
                    <Panel>
                        <EmptyState
                            message="No category with that code has any data."
                            action={
                                <Button variant="secondary" size="compact" to="/dashboard/categories">
                                    Back to categories
                                </Button>
                            }
                        />
                    </Panel>
                ) : !row ? (
                    <Skeleton count={4} variant="card" label="Loading category" />
                ) : (
                    <div className={`${styles.stack} ${summaryQuery.isPlaceholderData ? styles.stale : styles.fresh}`}>
                        <StatTiles
                            tiles={[
                                { label: 'Complaints', value: formatCount(row.complaintCount), note: windowDays ? `Reported in the last ${windowDays} days` : 'All time' },
                                { label: 'Active incidents', value: formatCount(row.activeIncidentCount), note: `${formatCount(row.highPriorityIncidentCount)} high priority` },
                                {
                                    label: 'Median time to fix',
                                    value: row.medianHoursToResolve === null ? '—' : formatHours(row.medianHoursToResolve),
                                    note: `${formatCount(row.resolvedIncidentCount)} fixed`,
                                },
                                { label: 'Default department', value: departmentName(row.defaultDepartmentId) ?? 'None', note: row.defaultDepartmentId ? undefined : 'Triage by hand' },
                            ]}
                        />

                        <div className={styles.twoUp}>
                            <Panel
                                heading={`Complaints reported — ${range.label.toLowerCase()}`}
                                as="section"
                                aside={
                                    <button type="button" className={styles.toggle} onClick={() => setTrendView(trendView === 'chart' ? 'table' : 'chart')}>
                                        {trendView === 'chart' ? 'Show table' : 'Show chart'}
                                    </button>
                                }
                            >
                                {trendQuery.data?.series[0] ? (
                                    <TrendChart
                                        points={trendQuery.data.series[0].points}
                                        bucket={trendQuery.data.bucket}
                                        label={`${name} complaints reported per ${trendQuery.data.bucket}`}
                                        view={trendView}
                                    />
                                ) : trendQuery.isError ? (
                                    <EmptyState tone="error" message={describeError(trendQuery.error, 'the trend').title} />
                                ) : (
                                    <Skeleton variant="block" height={168} label="Loading trend" />
                                )}
                            </Panel>

                            <Panel
                                heading="Where it is reported"
                                as="section"
                                aside={topWards.length === TOP_WARDS ? `Top ${TOP_WARDS} wards` : undefined}
                            >
                                {topWards.length === 0 ? (
                                    <EmptyState message="No complaints in this period." />
                                ) : (
                                    <DistributionBars
                                        label={`${name} complaints by ward`}
                                        data={topWards.map((w) => ({ key: w.wardId, label: `${w.wardCode} · ${w.wardName}`, value: w.complaintCount }))}
                                    />
                                )}
                            </Panel>
                        </div>

                        <Panel heading="Active incidents, most urgent first" flush as="section">
                            {incidentsQuery.isPending ? (
                                <div style={{ padding: 'var(--space-4)' }}>
                                    <Skeleton count={5} variant="row" label="Loading incidents" />
                                </div>
                            ) : (
                                <IncidentList
                                    incidents={incidentsQuery.data?.items ?? []}
                                    categoryLabel={categoryLabel}
                                    caption={`Active ${name} incidents, most urgent first`}
                                    emptyMessage="No active incidents in this category."
                                />
                            )}
                        </Panel>

                        {row.lastActivityAt && (
                            <p className={styles.note}>Last reported {formatRelative(row.lastActivityAt)}.</p>
                        )}
                    </div>
                )}
            </main>
        </div>
    );
}
