import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
    incidentService,
    type Incident,
    type IncidentFilters,
} from '@/services/incident.service';
import { categoryService } from '@/services/category.service';
import { wardService } from '@/services/ward.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount, humanizeEnum, truncate } from '@/lib/format';
import { daysSince } from '@/lib/datetime';
import { reportWindow } from '@/lib/reportWindow';
import { useDashboardStore } from '@/stores/dashboard.store';
import AppHeader from '@/components/AppHeader';
import { EXPLORER_NAV } from '@/components/explorer/ExplorerFilterBar';
import { incidentName } from '@/components/explorer/IncidentList';
import AttentionStrip from '@/components/dashboard/AttentionStrip';
import WardRail from '@/components/dashboard/WardRail';
import IncidentDetail from '@/components/dashboard/IncidentDetail';
import CopilotPanel from '@/components/copilot/CopilotPanel';
import Button from '@/components/ui/Button';
import DataTable, { type Column } from '@/components/ui/DataTable';
import EmptyState from '@/components/ui/EmptyState';
import MapView, { type MapMarker } from '@/components/ui/MapView';
import HazardChip from '@/components/ui/HazardChip';
import PriorityChip from '@/components/ui/PriorityChip';
import Skeleton from '@/components/ui/Skeleton';
import StatusChip from '@/components/ui/StatusChip';
import styles from './IncidentDashboard.module.css';

const QUEUE_PAGE_SIZE = 10;
const QUEUE_FETCH_LIMIT = 200;

export default function IncidentDashboard() {
    const selectedWardId = useDashboardStore((s) => s.selectedWardId);
    const statusFilter = useDashboardStore((s) => s.statusFilter);
    const bandFilter = useDashboardStore((s) => s.bandFilter);
    const categoryFilter = useDashboardStore((s) => s.categoryFilter);
    const selectedIncidentId = useDashboardStore((s) => s.selectedIncidentId);
    const selectIncident = useDashboardStore((s) => s.selectIncident);
    const windowDays = useDashboardStore((s) => s.windowDays);

    // Copilot panel visibility.
    const [copilotOpen, setCopilotOpen] = useState(false);

    // Debounced incident search.
    const [searchInput, setSearchInput] = useState('');
    const [search, setSearch] = useState('');

    useEffect(() => {
        const id = window.setTimeout(
            () => setSearch(searchInput.trim()),
            300,
        );

        return () => window.clearTimeout(id);
    }, [searchInput]);

    const wardsQuery = useQuery({
        queryKey: queryKeys.wards.list(),
        queryFn: () => wardService.list(),
        staleTime: 5 * 60_000,
    });

    const serverFilters = useMemo<IncidentFilters>(
        () => ({
            ...(selectedWardId !== null
                ? { wardIds: [selectedWardId] }
                : {}),
            ...(statusFilter !== null
                ? { statuses: [statusFilter] }
                : {}),
            ...(bandFilter !== null
                ? { priorityBands: [bandFilter] }
                : {}),
            ...(categoryFilter !== null
                ? { categories: [categoryFilter] }
                : {}),
            ...reportWindow(windowDays),
            ...(search !== '' ? { q: search } : {}),
            limit: QUEUE_FETCH_LIMIT,
        }),
        [
            selectedWardId,
            statusFilter,
            bandFilter,
            categoryFilter,
            windowDays,
            search,
        ],
    );

    const incidentsQuery = useQuery({
        queryKey: queryKeys.incidents.list(serverFilters),
        queryFn: () => incidentService.list(serverFilters),
        placeholderData: keepPreviousData,
    });

    const incidents = useMemo(
        () => incidentsQuery.data?.items ?? [],
        [incidentsQuery.data],
    );

    const total = incidentsQuery.data?.total ?? 0;

    const hasNarrowingFilters =
        statusFilter !== null ||
        bandFilter !== null ||
        categoryFilter !== null ||
        windowDays !== null ||
        search !== '';

    const categoriesQuery = useQuery({
        queryKey: queryKeys.categories.list(),
        queryFn: () => categoryService.list(),
        staleTime: 60 * 60_000,
    });

    const categories = useMemo(
        () => (categoriesQuery.data ?? []).map((c) => c.code),
        [categoriesQuery.data],
    );

    const categoryLabel = useMemo(() => {
        const names = new Map(
            (categoriesQuery.data ?? []).map((c) => [c.code, c.name]),
        );

        return (code: string) => names.get(code) ?? humanizeEnum(code);
    }, [categoriesQuery.data]);

    const markers = useMemo<MapMarker[]>(
        () =>
            incidents
                .filter(
                    (i): i is Incident & {
                        lat: number;
                        long: number;
                    } => i.lat !== null && i.long !== null,
                )
                .map((i) => ({
                    id: i.id,
                    lat: i.lat,
                    long: i.long,
                    band: i.priorityBand,
                    label: incidentName(i, categoryLabel),
                })),
        [incidents, categoryLabel],
    );

    const selected =
        incidents.find((i) => i.id === selectedIncidentId) ?? null;

    const columns = useMemo<Column<Incident>[]>(
        () => [
            {
                key: 'title',
                header: 'Incident',
                cell: (row) => (
                    <span className={styles.titleCell}>
                        {truncate(incidentName(row, categoryLabel), 44)}
                        <HazardChip incident={row} />
                    </span>
                ),
                sortValue: (row) => incidentName(row, categoryLabel),
            },
            {
                key: 'priority',
                header: 'Priority',
                cell: (row) => (
                    <PriorityChip
                        band={row.priorityBand}
                        score={row.priorityScore}
                    />
                ),
                sortValue: (row) => row.priorityScore,
                width: '140px',
            },
            {
                key: 'status',
                header: 'Status',
                cell: (row) => (
                    <StatusChip status={row.status} />
                ),
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
                    const days = row.firstReportedAt
                        ? daysSince(row.firstReportedAt)
                        : null;

                    return days === null ? '—' : `${days}d`;
                },
                sortValue: (row) =>
                    (
                        row.firstReportedAt
                            ? daysSince(row.firstReportedAt)
                            : null
                    ) ?? 0,
                align: 'end',
                width: '80px',
            },
        ],
        [categoryLabel],
    );

    const errorCopy = incidentsQuery.isError
        ? describeError(incidentsQuery.error, 'incidents')
        : null;

    /*
     * Reuse the dashboard's reporting-window helper.
     * Only include dates if that helper returns fields named "from" and "to".
     */
    const copilotWindow = reportWindow(windowDays) as Record<
        string,
        unknown
    >;

    const copilotScope = {
        ...(selectedWardId !== null
            ? { wardId: String(selectedWardId) }
            : {}),
        ...(categoryFilter !== null
            ? { category: categoryFilter }
            : {}),
        ...(typeof copilotWindow.from === 'string'
            ? { from: copilotWindow.from }
            : {}),
        ...(typeof copilotWindow.to === 'string'
            ? { to: copilotWindow.to }
            : {}),
    };

    return (
        <div className={styles.page}>
            <AppHeader wide nav={EXPLORER_NAV} />

            <div
                className={
                    selected
                        ? styles.layoutWithDetail
                        : styles.layout
                }
            >
                <aside
                    className={styles.rail}
                    aria-label="Wards and filters"
                >
                    {wardsQuery.isPending ? (
                        <div className={styles.railLoading}>
                            <Skeleton
                                count={5}
                                variant="row"
                                label="Loading wards"
                            />
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
                    <div>
                        <AttentionStrip
                            wardId={selectedWardId}
                            category={categoryFilter}
                            categoryLabel={categoryLabel}
                        />
                    </div>

                    <div className={styles.map}>
                        <MapView
                            ariaLabel="Incidents on a map"
                            fitToMarkers
                            markers={markers}
                            selectedId={selectedIncidentId}
                            onSelect={selectIncident}
                        />
                    </div>

                    <section
                        className={styles.queue}
                        aria-labelledby="queue-heading"
                    >
                        <div className={styles.queueHead}>
                            <h2
                                className={styles.queueTitle}
                                id="queue-heading"
                            >
                                Incident queue
                            </h2>

                            <Button
                                variant="secondary"
                                size="compact"
                                onClick={() =>
                                    setCopilotOpen((open) => !open)
                                }
                                aria-expanded={copilotOpen}
                                aria-controls="dashboard-copilot"
                            >
                                {copilotOpen
                                    ? 'Close Copilot'
                                    : 'Ask Copilot'}
                            </Button>

                            <input
                                type="search"
                                className={styles.search}
                                placeholder="Search titles and summaries"
                                aria-label="Search incidents"
                                value={searchInput}
                                onChange={(event) =>
                                    setSearchInput(event.target.value)
                                }
                            />

                            <span className={styles.queueCount}>
                                {total > incidents.length
                                    ? `${formatCount(incidents.length)} of ${formatCount(total)} incidents`
                                    : `${formatCount(total)}${total === 1 ? ' incident' : ' incidents'}`}
                            </span>
                        </div>

                        {incidentsQuery.isPending ? (
                            <div className={styles.queueLoading}>
                                <Skeleton
                                    count={8}
                                    variant="row"
                                    label="Loading incidents"
                                />
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
                                        onClick={() =>
                                            void incidentsQuery.refetch()
                                        }
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
                                onSelect={(row) =>
                                    selectIncident(row.id)
                                }
                                initialSort={{
                                    key: 'priority',
                                    direction: 'desc',
                                }}
                                pageSize={QUEUE_PAGE_SIZE}
                            />
                        )}
                    </section>

                    {copilotOpen && (
                        <section
                            id="dashboard-copilot"
                            aria-label="Copilot assistant"
                            style={{ marginTop: '20px' }}
                        >
                            <CopilotPanel
                                scope={copilotScope}
                                onClose={() => setCopilotOpen(false)}
                                onOpenIncident={(incidentId) => {
                                    selectIncident(incidentId);
                                }}
                                onNavigate={(path) => {
                                    window.location.assign(path);
                                }}
                            />
                        </section>
                    )}
                </main>

                {selected && (
                    <aside
                        className={styles.detail}
                        aria-label="Incident details"
                    >
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