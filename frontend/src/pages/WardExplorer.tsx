import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { explorerService, type WardExplorerSummary } from '@/services/explorer.service';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount } from '@/lib/format';
import { formatRelative, parseServerDate } from '@/lib/datetime';
import { reportWindow } from '@/lib/reportWindow';
import { useCategoryRegistry } from '@/lib/useRegistry';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import ExplorerFilterBar, { EXPLORER_NAV } from '@/components/explorer/ExplorerFilterBar';
import WardShapes, { DensityLegend } from '@/components/explorer/WardShapes';
import Button from '@/components/ui/Button';
import DataTable, { type Column } from '@/components/ui/DataTable';
import EmptyState from '@/components/ui/EmptyState';
import MapView from '@/components/ui/MapView';
import Panel from '@/components/ui/Panel';
import Skeleton from '@/components/ui/Skeleton';
import styles from './Explorer.module.css';

const BENGALURU: [number, number] = [12.9716, 77.5946];

/**
 * Ward Explorer (roadmap §11): what is happening across each ward?
 *
 * One request (GET /dashboard/wards) feeds both the map and the table, so
 * the two can never disagree. The map shades by active incidents; the table
 * is its accessible twin and carries every figure.
 */
export default function WardExplorer() {
    const navigate = useNavigate();
    const windowDays = useDashboardStore((s) => s.windowDays);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const { label: categoryLabel } = useCategoryRegistry();
    const [showQuiet, setShowQuiet] = useState(false);

    const filters = useMemo(
        () => ({
            ...reportWindow(windowDays),
            ...(categoryFilter ? { categories: [categoryFilter] } : {}),
        }),
        [windowDays, categoryFilter]
    );

    const wardsQuery = useQuery({
        queryKey: queryKeys.dashboard.wards(filters),
        queryFn: () => explorerService.wards(filters),
        placeholderData: keepPreviousData,
    });

    const boundariesQuery = useQuery({
        queryKey: queryKeys.wards.boundaries(),
        queryFn: () => wardService.boundaries(),
        // Changes only with a new delimitation import.
        staleTime: Infinity,
    });

    const rows = useMemo(() => wardsQuery.data ?? [], [wardsQuery.data]);
    const counts = useMemo(
        () => new Map(rows.map((r) => [r.wardId, r.activeIncidentCount])),
        [rows]
    );
    const quiet = rows.filter((r) => r.complaintCount === 0 && r.activeIncidentCount === 0).length;
    const visible = useMemo(
        () => (showQuiet ? rows : rows.filter((r) => r.complaintCount > 0 || r.activeIncidentCount > 0)),
        [rows, showQuiet]
    );

    const columns = useMemo<Column<WardExplorerSummary>[]>(
        () => [
            {
                key: 'ward',
                header: 'Ward',
                cell: (r) => (
                    <>
                        <span className={styles.wardCode}>{r.wardCode}</span> {r.wardName}
                    </>
                ),
                sortValue: (r) => r.wardName,
                width: '240px',
                minWidth: '200px',
            },
            {
                key: 'complaints',
                header: 'Complaints',
                cell: (r) => formatCount(r.complaintCount),
                sortValue: (r) => r.complaintCount,
                align: 'end',
                width: '110px',
            },
            {
                key: 'active',
                header: 'Active incidents',
                cell: (r) => formatCount(r.activeIncidentCount),
                sortValue: (r) => r.activeIncidentCount,
                align: 'end',
                width: '130px',
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
                key: 'dominant',
                header: 'Main category',
                cell: (r) => (r.dominantCategory ? categoryLabel(r.dominantCategory) : '—'),
                sortValue: (r) => (r.dominantCategory ? categoryLabel(r.dominantCategory) : ''),
                width: '180px',
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
        [categoryLabel]
    );

    const error = wardsQuery.isError ? describeError(wardsQuery.error, 'ward figures') : null;
    const frameClass = wardsQuery.isPlaceholderData ? styles.stale : styles.fresh;

    return (
        <div className={styles.page}>
            <AppHeader wide nav={EXPLORER_NAV} />
            <main className={styles.main}>
                <div className={styles.head}>
                    <h1 className={styles.title}>Wards</h1>
                    <ExplorerFilterBar category />
                </div>

                {error ? (
                    <Panel>
                        <EmptyState
                            tone="error"
                            message={error.title}
                            detail={error.detail}
                            action={
                                <Button variant="secondary" size="compact" onClick={() => void wardsQuery.refetch()}>
                                    Try again
                                </Button>
                            }
                        />
                    </Panel>
                ) : (
                    <div className={`${styles.split} ${frameClass}`}>
                        <Panel heading="Active incidents by ward" flush as="section">
                            <div className={styles.mapBox}>
                                {boundariesQuery.data ? (
                                    <MapView ariaLabel="Map of wards shaded by active incidents" center={BENGALURU} zoom={11}>
                                        <WardShapes
                                            fitAll
                                            boundaries={boundariesQuery.data}
                                            counts={counts}
                                            countLabel="active incidents"
                                            onSelect={(id) => navigate(`/dashboard/wards/${id}`)}
                                        />
                                    </MapView>
                                ) : boundariesQuery.isError ? (
                                    <EmptyState message="Ward boundaries could not be loaded." detail="The table below has every figure." />
                                ) : (
                                    <Skeleton variant="block" height={440} label="Loading ward boundaries" />
                                )}
                            </div>
                            <DensityLegend title="Active incidents" />
                            {boundariesQuery.data && (
                                <p className={styles.note} style={{ padding: 'var(--space-2) var(--space-4)' }}>
                                    Boundaries: {boundariesQuery.data.attribution}
                                </p>
                            )}
                        </Panel>

                        <Panel
                            heading="Ward table"
                            flush
                            as="section"
                            aside={
                                quiet > 0 ? (
                                    <label className={styles.check}>
                                        <input
                                            type="checkbox"
                                            checked={showQuiet}
                                            onChange={(e) => setShowQuiet(e.target.checked)}
                                        />
                                        Show {formatCount(quiet)} wards with no activity
                                    </label>
                                ) : undefined
                            }
                        >
                            {wardsQuery.isPending ? (
                                <div style={{ padding: 'var(--space-4)' }}>
                                    <Skeleton count={8} variant="row" label="Loading wards" />
                                </div>
                            ) : (
                                <DataTable
                                    caption="Wards with complaint and incident counts, sortable"
                                    columns={columns}
                                    rows={visible}
                                    rowKey={(r) => r.wardId}
                                    onSelect={(r) => navigate(`/dashboard/wards/${r.wardId}`)}
                                    initialSort={{ key: 'active', direction: 'desc' }}
                                    pageSize={20}
                                    emptyMessage={
                                        windowDays !== null || categoryFilter !== null
                                            ? 'No ward has activity matching these filters.'
                                            : 'No ward has any activity yet.'
                                    }
                                />
                            )}
                        </Panel>
                    </div>
                )}
            </main>
        </div>
    );
}
