import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { incidentService, type Incident } from '@/services/incident.service';
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
 * Paged on the client: the endpoint returns the whole filtered set, so sorting
 * still runs over every incident rather than only the visible page — which is
 * what keeps "most urgent first" honest.
 */
const QUEUE_PAGE_SIZE = 10;

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

    // Ward and status are server-side filters; band and category are applied
    // below. Keeping them in the key means each combination caches separately.
    const serverFilters = useMemo(
        () => ({
            ...(selectedWardId !== null ? { wardId: selectedWardId } : {}),
            ...(statusFilter !== null ? { status: statusFilter } : {}),
        }),
        [selectedWardId, statusFilter]
    );

    const incidentsQuery = useQuery({
        queryKey: queryKeys.incidents.list(serverFilters),
        queryFn: () => incidentService.list(serverFilters),
    });

    const incidents = useMemo(() => incidentsQuery.data ?? [], [incidentsQuery.data]);

    // Category options come from what actually loaded. There is no categories
    // endpoint in the contract, and a hardcoded list here would show filters
    // that match nothing.
    const categories = useMemo(
        () => [...new Set(incidents.map((i) => i.category))].sort(),
        [incidents]
    );

    // Band has no server-side equivalent — the API takes minPriority, a number,
    // not a band — so it is applied here rather than faked as a query param.
    const filtered = useMemo(
        () =>
            incidents.filter(
                (incident) =>
                    (bandFilter === null || incident.priorityBand === bandFilter) &&
                    (categoryFilter === null || incident.category === categoryFilter)
            ),
        [incidents, bandFilter, categoryFilter]
    );

    const markers = useMemo<MapMarker[]>(
        () =>
            filtered
                .filter(
                    (i): i is Incident & { lat: number; long: number } =>
                        i.lat !== null && i.long !== null
                )
                .map((i) => ({
                    id: i.id,
                    lat: i.lat,
                    long: i.long,
                    band: i.priorityBand,
                    label: i.title ?? humanizeEnum(i.category),
                })),
        [filtered]
    );

    const selected = filtered.find((i) => i.id === selectedIncidentId) ?? null;

    const columns = useMemo<Column<Incident>[]>(
        () => [
            {
                key: 'title',
                header: 'Incident',
                cell: (row) => truncate(row.title ?? humanizeEnum(row.category), 44),
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
                cell: (row) => {
                    const days = daysSince(row.createdAt);
                    return days === null ? '—' : `${days}d`;
                },
                sortValue: (row) => daysSince(row.createdAt) ?? 0,
                align: 'end',
                width: '80px',
            },
        ],
        []
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
                        <WardRail wards={wardsQuery.data ?? []} categories={categories} />
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
                                {formatCount(filtered.length)}
                                {filtered.length === 1 ? ' incident' : ' incidents'}
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
                        ) : filtered.length === 0 ? (
                            <EmptyState
                                message={
                                    incidents.length === 0
                                        ? 'No incidents in this ward yet.'
                                        : 'No incidents match these filters.'
                                }
                            />
                        ) : (
                            <DataTable
                                caption="Incident queue, sortable"
                                columns={columns}
                                rows={filtered}
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
                        <IncidentDetail incident={selected} onClose={() => selectIncident(null)} />
                    </aside>
                )}
            </div>
        </div>
    );
}
