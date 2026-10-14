import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { incidentService, type Incident, type IncidentFilters } from '@/services/incident.service';
import { categoryService } from '@/services/category.service';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount, humanizeEnum, truncate } from '@/lib/format';
import { daysSince } from '@/lib/datetime';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import WardRail from '@/components/dashboard/WardRail';
import IncidentDetail from '@/components/dashboard/IncidentDetail';
import Button from '@/components/ui/Button';
import DataTable, { type Column } from '@/components/ui/DataTable';
import EmptyState from '@/components/ui/EmptyState';
import MapView, { type MapMarker } from '@/components/ui/MapView';
import PriorityChip from '@/components/ui/PriorityChip';
import Skeleton from '@/components/ui/Skeleton';
import StatusChip from '@/components/ui/StatusChip';
import styles from "./IncidentDashboard.module.css";

/**
 * Rows per page in the queue.
 *
 * Paged on the client over one server page of up to QUEUE_FETCH_LIMIT
 * incidents, so re-sorting a column still runs over every loaded incident
 * rather than only the visible ten.
 */
const QUEUE_PAGE_SIZE = 10;

/**
 * How many incidents the console loads per filter combination — the server's
 * maximum page. The map needs every marker in the filtered set, so the console
 * asks for all of it in one page; when `total` exceeds this the queue says so
 * instead of implying it is showing everything. Past that scale the map moves
 * to per-ward aggregates (docs/api-contract.md, N1).
 */
const QUEUE_FETCH_LIMIT = 200;

/**
 * Three regions, all visible at once, no tabs. The map and the queue are two
 * views of the same filtered set: selecting a marker highlights the row and
 * vice versa, through one piece of store state.
 */
export default function IncidentDashboard() {
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const statusFilter = useDashboardStore((s) => s.statusFilter);
    const bandFilter = useDashboardStore((s) => s.bandFilter);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const selectedIncidentId = useDashboardStore((s) => s.selectedIncidentId);
    const selectIncident = useDashboardStore((s) => s.selectIncident);

    const wardsQuery = useQuery({
        queryKey: queryKeys.wards.list(),
        queryFn: () => wardService.list(),
        staleTime: 5 * 60_000,
    });

    // Every filter is applied by the server, including band — the thresholds
    // are the model's, so the client never compares scores itself. Keeping the
    // filters in the key means each combination caches separately.
    const serverFilters = useMemo<IncidentFilters>(
        () => ({
            ...(selectedWardId !== null ? { wardIds: [selectedWardId] } : {}),
            ...(statusFilter !== null ? { statuses: [statusFilter] } : {}),
            ...(bandFilter !== null ? { priorityBands: [bandFilter] } : {}),
            ...(categoryFilter !== null ? { categories: [categoryFilter] } : {}),
            limit: QUEUE_FETCH_LIMIT,
        }),
        [selectedWardId, statusFilter, bandFilter, categoryFilter]
    );

    const incidentsQuery = useQuery({
        queryKey: queryKeys.incidents.list(serverFilters),
        queryFn: () => incidentService.list(serverFilters),
    });

    const incidents = useMemo(() => incidentsQuery.data?.items ?? [], [incidentsQuery.data]);
    const total = incidentsQuery.data?.total ?? 0;
    const hasNarrowingFilters = statusFilter !== null || bandFilter !== null || categoryFilter !== null;

    const categoriesQuery = useQuery({
        queryKey: queryKeys.categories.list(),
        queryFn: () => categoryService.list(),
        staleTime: 60 * 60_000,
    });

    // The category facets are the registry's codes, in registry order. They can
    // no longer come from what loaded: with category filtered on the server,
    // the loaded set narrows to the selected category and every other chip
    // would vanish. A chip that matches nothing lands on the "no incidents
    // match these filters" state, which says exactly that.
    const categories = useMemo(
        () => (categoriesQuery.data ?? []).map((c) => c.code),
        [categoriesQuery.data]
    );

    /**
     * Canonical code -> the officer-facing name in the registry.
     *
     * Falls back to humanizing the code itself, which is what an incident
     * carrying a value the registry does not know (a row the V11 backfill left
     * alone) needs — it is still real data and still belongs on screen.
     */
    const categoryLabel = useMemo(() => {
        const names = new Map((categoriesQuery.data ?? []).map((c) => [c.code, c.name]));
        return (code: string) => names.get(code) ?? humanizeEnum(code);
    }, [categoriesQuery.data]);

    const markers = useMemo<MapMarker[]>(
        () =>
            incidents
                .filter(
                    (i): i is Incident & { lat: number; long: number } =>
                        i.lat !== null && i.long !== null
                )
                .map((i) => ({
                    id: i.id,
                    lat: i.lat,
                    long: i.long,
                    band: i.priorityBand,
                    label: i.title ?? categoryLabel(i.category),
                })),
        // categoryLabel belongs here: it changes exactly once, when /categories
        // resolves, and that is precisely when these labels must be rebuilt.
        [incidents, categoryLabel]
    );

    const selected = incidents.find((i) => i.id === selectedIncidentId) ?? null;

    const columns = useMemo<Column<Incident>[]>(
        () => [
            {
                key: 'title',
                header: 'Incident',
                cell: (row) => truncate(row.title ?? categoryLabel(row.category), 44),
                sortValue: (row) => row.title ?? row.category,
            },
            {
                key: 'priority',
                header: 'Priority',
                cell: (row) => <PriorityChip band={row.priorityBand} score={row.priorityScore} />,
                sortValue: (row) => row.priorityScore,
                width: '140px',
            },
            {
                key: 'status',
                header: 'Status',
                cell: (row) => <StatusChip status={row.status} />,
                sortValue: (row) => row.status,
                width: '120px',
            },
            {
                key: 'reports',
                header: 'Reports',
                cell: (row) => formatCount(row.complaintCount),
                sortValue: (row) => row.complaintCount,
                align: 'end',
                width: '90px',
            },
            {
                key: 'age',
                header: 'Age',
                // Age of the PROBLEM — since it was first reported — not of the
                // grouping, which createdAt would measure.
                cell: (row) => {
                    const days = row.firstReportedAt ? daysSince(row.firstReportedAt) : null;
                    return days === null ? '—' : `${days}d`;
                },
                sortValue: (row) => (row.firstReportedAt ? daysSince(row.firstReportedAt) : null) ?? 0,
                align: 'end',
                width: '80px',
            },
        ],
        // categoryLabel is the only value these cells close over. It changes
        // once, when /categories resolves; without it here the table would keep
        // rendering raw codes for the life of the page.
        [categoryLabel]
    );

    const errorCopy = incidentsQuery.isError
        ? describeError(incidentsQuery.error, 'incidents')
        : null;

    return (
        <div className={styles.page}>
            <AppHeader wide />

            <div className={selected ? styles.layoutWithDetail : styles.layout}>
                <aside className={styles.rail} aria-label="Wards and filters">
                    {wardsQuery.isPending ? (
                        <div className={styles.railLoading}>
                            <Skeleton count={5} variant="row" label="Loading wards" />
                        </div>
                    ) : (
                        <WardRail
                            wards={wardsQuery.data ?? []}
                            categories={categories}
                            categoryLabel={categoryLabel}
                        />
                    )}
                </aside>

                <main className={styles.center}>
                    <div className={styles.map}>
                        <MapView
                            ariaLabel="Incidents on a map"
                            markers={markers}
                            selectedId={selectedIncidentId}
                            onSelect={selectIncident}
                        />
                    </div>

                    <section className={styles.queue} aria-labelledby="queue-heading">
                        <div className={styles.queueHead}>
                            <h2 className={styles.queueTitle} id="queue-heading">
                                Incident queue
                            </h2>
                            <span className={styles.queueCount}>
                                {total > incidents.length
                                    ? `${formatCount(incidents.length)} of ${formatCount(total)} incidents`
                                    : `${formatCount(total)}${total === 1 ? ' incident' : ' incidents'}`}
                            </span>
                        </div>

                        {incidentsQuery.isPending ? (
                            <div className={styles.queueLoading}>
                                <Skeleton count={8} variant="row" label="Loading incidents" />
                            </div>
                        ) : errorCopy !== null ? (
                            <EmptyState
                                tone="error"
                                message={errorCopy.title}
                                detail={errorCopy.detail}
                                action={
                                    <Button
                                        variant="secondary"
                                        size="compact"
                                        onClick={() => void incidentsQuery.refetch()}
                                    >
                                        Try again
                                    </Button>
                                }
                            />
                        ) : incidents.length === 0 ? (
                            <EmptyState
                                message={
                                    hasNarrowingFilters
                                        ? 'No incidents match these filters.'
                                        : 'No incidents in this ward yet.'
                                }
                            />
                        ) : (
                            <DataTable
                                caption="Incident queue, sortable"
                                columns={columns}
                                rows={incidents}
                                rowKey={(row) => row.id}
                                selectedId={selectedIncidentId}
                                onSelect={(row) => selectIncident(row.id)}
                                initialSort={{ key: "priority", direction: "desc" }}
                                pageSize={QUEUE_PAGE_SIZE}
                            />
                        )}
                    </section>
                </main>

                {selected && (
                    <aside className={styles.detail} aria-label="Incident details">
                        <IncidentDetail
                            incident={selected}
                            categoryLabel={categoryLabel}
                            onClose={() => selectIncident(null)}
                        />
                    </aside>
                )}
            </div>
        </div>
    );
}
