import { useMemo } from 'react';
import type { Hotspot, Hotspots } from '@/services/explorer.service';
import { formatCount } from '@/lib/format';
import { formatAbsolute, formatRelative } from '@/lib/datetime';
import { useOpenIncident } from '@/lib/useOpenIncident';
import DataTable, { type Column } from '@/components/ui/DataTable';
import PriorityChip from '@/components/ui/PriorityChip';
import { placeName } from './IncidentList';
import styles from './HotspotList.module.css';

interface Props {
    data: Hotspots;
    categoryLabel: (code: string) => string;
}

/**
 * The hotspot list beside the ward map. Every hotspot opens its incidents:
 * selecting the row opens the highest-priority one in the queue, and each
 * member has its own link — a hotspot an officer cannot open is a claim they
 * cannot check.
 */
export default function HotspotList({ data, categoryLabel }: Props) {
    const openIncident = useOpenIncident();

    const columns = useMemo<Column<Hotspot>[]>(
        () => [
            {
                key: 'category',
                header: 'Hotspot',
                cell: (h) => (
                    <>
                        <strong>{categoryLabel(h.category)}</strong>
                        <div className={styles.where}>{h.wards.map((w) => w.name).join(', ')}</div>
                    </>
                ),
                sortValue: (h) => categoryLabel(h.category),
                minWidth: '200px',
            },
            {
                key: 'priority',
                header: 'Highest priority',
                cell: (h) => <PriorityChip band={h.maxPriorityBand} score={h.maxPriorityScore} />,
                sortValue: (h) => h.maxPriorityScore,
                width: '150px',
            },
            {
                key: 'incidents',
                header: 'Incidents',
                cell: (h) => (
                    <ul className={styles.members} aria-label={`${h.incidentCount} incidents`}>
                        {h.incidents.map((m) => (
                            <li key={m.id}>
                                <button
                                    type="button"
                                    className={styles.member}
                                    onClick={(e) => {
                                        // The row's own select would open a different member.
                                        e.stopPropagation();
                                        openIncident(m.id, m.wardId);
                                    }}
                                >
                                    {placeName(m.address) ?? `Incident ${m.id}`}
                                    <span className={styles.count}> · {formatCount(m.complaintCount)}</span>
                                </button>
                            </li>
                        ))}
                    </ul>
                ),
                sortValue: (h) => h.incidentCount,
                minWidth: '220px',
            },
            {
                key: 'reports',
                header: 'Reports',
                cell: (h) => formatCount(h.complaintCount),
                sortValue: (h) => h.complaintCount,
                align: 'end',
                width: '90px',
            },
            {
                key: 'spread',
                header: 'Spread',
                cell: (h) => `${formatCount(Math.round(h.radiusMetres))} m`,
                sortValue: (h) => h.radiusMetres,
                align: 'end',
                width: '90px',
            },
            {
                key: 'last',
                header: 'Last report',
                cell: (h) => (h.lastReportedAt ? formatRelative(h.lastReportedAt) : '—'),
                align: 'end',
                width: '130px',
            },
        ],
        [categoryLabel, openIncident]
    );

    const { minIncidents, radiusMetres, from } = data.definition;

    return (
        <>
            <p className={styles.definition}>
                {formatCount(minIncidents)} or more active incidents of one category within{' '}
                {formatCount(radiusMetres)} m of each other
                {from ? `, each reported since ${formatAbsolute(from)}` : ''}.
            </p>
            <DataTable
                caption="Hotspots: clusters of active incidents of one category"
                columns={columns}
                rows={data.hotspots}
                rowKey={(h) => h.id}
                onSelect={(h) => {
                    const top = h.incidents[0];
                    if (top) openIncident(top.id, top.wardId);
                }}
                initialSort={{ key: 'priority', direction: 'desc' }}
                emptyMessage="No hotspots: no category has that many active incidents that close together."
            />
        </>
    );
}
