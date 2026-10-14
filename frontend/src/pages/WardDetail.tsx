import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { explorerService } from '@/services/explorer.service';
import { incidentService } from '@/services/incident.service';
import { wardService } from '@/services/ward.service';
import { ApiError } from '@/services/api';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import { PRIORITY_BAND_ORDER, priorityLabel } from '@/lib/priority';
import { reportWindow, trendRange } from '@/lib/reportWindow';
import { useCategoryRegistry } from '@/lib/useRegistry';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import ExplorerFilterBar, { EXPLORER_NAV } from '@/components/explorer/ExplorerFilterBar';
import { DistributionBars, StatTiles } from '@/components/explorer/Figures';
import IncidentList, { incidentName } from '@/components/explorer/IncidentList';
import TrendChart from '@/components/explorer/TrendChart';
import WardShapes from '@/components/explorer/WardShapes';
import Button from '@/components/ui/Button';
import EmptyState from '@/components/ui/EmptyState';
import MapView, { type MapMarker } from '@/components/ui/MapView';
import Panel from '@/components/ui/Panel';
import Skeleton from '@/components/ui/Skeleton';
import styles from './Explorer.module.css';

const STATUS_ORDER = ['open', 'in_progress', 'resolved', 'closed'] as const;
const STATUS_LABEL: Record<(typeof STATUS_ORDER)[number], string> = {
    open: 'Open',
    in_progress: 'In progress',
    resolved: 'Resolved (fixed)',
    closed: 'Closed (no fix)',
};

/**
 * Ward detail (roadmap §12): metadata → incident summary → category
 * distribution → trend → priority distribution → recent incidents → map.
 *
 * Same filters as every other view; no second filtering architecture. Each
 * figure comes from the endpoint the classification assigned it — category
 * distribution is GET /dashboard/categories?wardId=, not a field on the ward
 * summary, so it cannot drift from the Category Explorer.
 */
export default function WardDetail() {
    const { wardId = '' } = useParams();
    const navigate = useNavigate();
    const windowDays = useDashboardStore((s) => s.windowDays);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const clearFilters = useDashboardStore((s) => s.clearFilters);
    const selectWard = useDashboardStore((s) => s.selectWard);
    const { label: categoryLabel } = useCategoryRegistry();
    const [trendView, setTrendView] = useState<'chart' | 'table'>('chart');

    const reported = useMemo(() => reportWindow(windowDays), [windowDays]);
    const categories = useMemo(() => (categoryFilter ? [categoryFilter] : undefined), [categoryFilter]);
    const range = useMemo(() => trendRange(windowDays), [windowDays]);

    const summaryFilters = useMemo(() => ({ ...reported, categories }), [reported, categories]);
    const summaryQuery = useQuery({
        queryKey: queryKeys.dashboard.ward(wardId, summaryFilters),
        queryFn: () => explorerService.wardSummary(wardId, summaryFilters),
        placeholderData: keepPreviousData,
        retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
    });

    const categoryFilters = useMemo(() => ({ ...reported, wardIds: [wardId] }), [reported, wardId]);
    const categoriesQuery = useQuery({
        queryKey: queryKeys.dashboard.categories(categoryFilters),
        queryFn: () => explorerService.categories(categoryFilters),
        placeholderData: keepPreviousData,
    });

    const trendFilters = useMemo(
        () => ({ from: range.from, to: range.to, bucket: range.bucket, wardIds: [wardId], categories }),
        [range, wardId, categories]
    );
    const trendQuery = useQuery({
        queryKey: queryKeys.dashboard.trends(trendFilters),
        queryFn: () => explorerService.trends(trendFilters),
        placeholderData: keepPreviousData,
    });

    const incidentFilters = useMemo(
        () => ({
            ...reported,
            wardIds: [wardId],
            categories,
            sort: 'lastReportedAt' as const,
            limit: 10,
        }),
        [reported, wardId, categories]
    );
    const incidentsQuery = useQuery({
        queryKey: queryKeys.incidents.list(incidentFilters),
        queryFn: () => incidentService.list(incidentFilters),
        placeholderData: keepPreviousData,
    });

    const boundariesQuery = useQuery({
        queryKey: queryKeys.wards.boundaries(),
        queryFn: () => wardService.boundaries(),
        staleTime: Infinity,
    });

    const summary = summaryQuery.data;
    const categoryRows = (categoriesQuery.data ?? []).filter((c) => c.complaintCount > 0);
    const complaintTotal = categoryRows.reduce((sum, c) => sum + c.complaintCount, 0);
    const incidents = useMemo(() => incidentsQuery.data?.items ?? [], [incidentsQuery.data]);
    const markers = useMemo<MapMarker[]>(
        () =>
            incidents
                .filter((i): i is typeof i & { lat: number; long: number } => i.lat !== null && i.long !== null)
                .map((i) => ({ id: i.id, lat: i.lat, long: i.long, band: i.priorityBand, label: incidentName(i, categoryLabel) })),
        [incidents, categoryLabel]
    );

    if (summaryQuery.isError) {
        const notFound = summaryQuery.error instanceof ApiError && summaryQuery.error.status === 404;
        const err = describeError(summaryQuery.error, 'this ward');
        return (
            <div className={styles.page}>
                <AppHeader wide nav={EXPLORER_NAV} />
                <main className={styles.main}>
                    <Panel>
                        <EmptyState
                            tone={notFound ? 'neutral' : 'error'}
                            message={notFound ? 'There is no active ward with that number.' : err.title}
                            detail={notFound ? undefined : err.detail}
                            action={
                                <Button variant="secondary" size="compact" to="/dashboard/wards">
                                    Back to wards
                                </Button>
                            }
                        />
                    </Panel>
                </main>
            </div>
        );
    }

    const active = summary ? (summary.statusDistribution.open ?? 0) + (summary.statusDistribution.in_progress ?? 0) : 0;
    const frameClass = summaryQuery.isPlaceholderData ? styles.stale : styles.fresh;

    return (
        <div className={styles.page}>
            <AppHeader wide nav={EXPLORER_NAV} />
            <main className={styles.main}>
                <div className={styles.head}>
                    <p className={styles.crumb}>
                        <Link to="/dashboard/wards">Wards</Link> / {summary ? `Ward ${summary.ward.code}` : '…'}
                    </p>
                    <div className={styles.titleRow}>
                        <h1 className={styles.title}>{summary ? summary.ward.name : 'Ward'}</h1>
                        <button
                            type="button"
                            className={styles.linkButton}
                            onClick={() => {
                                clearFilters();
                                selectWard(wardId);
                                navigate('/dashboard');
                            }}
                        >
                            Open this ward in the incident queue
                        </button>
                    </div>
                    <ExplorerFilterBar category />
                </div>

                {!summary ? (
                    <Skeleton count={4} variant="card" label="Loading ward" />
                ) : (
                    <div className={`${styles.stack} ${frameClass}`}>
                        <StatTiles
                            tiles={[
                                { label: 'Complaints', value: formatCount(complaintTotal), note: windowDays ? `Reported in the last ${windowDays} days` : 'All time' },
                                { label: 'Active incidents', value: formatCount(active), note: 'Open or in progress' },
                                { label: 'High priority', value: formatCount(summary.highPriorityIncidentCount), note: 'Active, high or critical band' },
                                { label: 'Last report', value: summary.lastActivityAt ? formatRelative(summary.lastActivityAt) : '—' },
                            ]}
                        />

                        <div className={styles.twoUp}>
                            <Panel heading="Complaints by category" as="section">
                                {categoryRows.length === 0 ? (
                                    <EmptyState message="No complaints in this period." />
                                ) : (
                                    <DistributionBars
                                        label="Complaints by category"
                                        data={categoryRows.map((c) => ({ key: c.category, label: categoryLabel(c.category), value: c.complaintCount }))}
                                    />
                                )}
                            </Panel>

                            <Panel heading="Active incidents by priority" as="section">
                                <DistributionBars
                                    label="Active incidents by priority band"
                                    data={PRIORITY_BAND_ORDER.map((band) => ({
                                        key: band,
                                        label: priorityLabel(band),
                                        value: summary.priorityDistribution[band] ?? 0,
                                        tone: band,
                                    }))}
                                />
                            </Panel>
                        </div>

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
                                        label={`Complaints reported per ${trendQuery.data.bucket} in this ward`}
                                        view={trendView}
                                    />
                                ) : trendQuery.isError ? (
                                    <EmptyState tone="error" message={describeError(trendQuery.error, 'the trend').title} />
                                ) : (
                                    <Skeleton variant="block" height={168} label="Loading trend" />
                                )}
                            </Panel>

                            <Panel heading="Incidents by status" as="section">
                                <DistributionBars
                                    label="Incidents by status"
                                    data={STATUS_ORDER.map((status) => ({
                                        key: status,
                                        label: STATUS_LABEL[status],
                                        value: summary.statusDistribution[status] ?? 0,
                                    }))}
                                />
                            </Panel>
                        </div>

                        <div className={styles.split}>
                            <Panel heading="Map" flush as="section">
                                <div className={styles.mapBoxSmall}>
                                    <MapView ariaLabel={`Map of ${summary.ward.name} with its recent incidents`} markers={markers} zoom={13}>
                                        {boundariesQuery.data && (
                                            <WardShapes boundaries={boundariesQuery.data} highlightWardId={wardId} />
                                        )}
                                    </MapView>
                                </div>
                            </Panel>

                            <Panel heading="Recently reported incidents" flush as="section">
                                {incidentsQuery.isPending ? (
                                    <div style={{ padding: 'var(--space-4)' }}>
                                        <Skeleton count={5} variant="row" label="Loading incidents" />
                                    </div>
                                ) : (
                                    <IncidentList
                                        incidents={incidents}
                                        categoryLabel={categoryLabel}
                                        caption="Most recently reported incidents in this ward"
                                        emptyMessage="No incidents in this period."
                                    />
                                )}
                            </Panel>
                        </div>
                    </div>
                )}
            </main>
        </div>
    );
}
