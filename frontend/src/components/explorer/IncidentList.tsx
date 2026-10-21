import { useMemo } from 'react';
import type { Incident } from '@/services/incident.service';
import { formatCount, truncate } from '@/lib/format';
import { formatRelative } from '@/lib/datetime';
import { useOpenIncident } from '@/lib/useOpenIncident';
import DataTable, { type Column } from '@/components/ui/DataTable';
import PriorityChip from '@/components/ui/PriorityChip';
import StatusChip from '@/components/ui/StatusChip';

interface Props {
    incidents: readonly Incident[];
    categoryLabel: (code: string) => string;
    caption: string;
    emptyMessage: string;
}

/** A Google plus code ("4J24+6C6", "WHJX+V66"), which geocoding puts first when it has no street. */
const PLUS_CODE = /^[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}\s*/i;

/**
 * The most specific human-readable place in a geocoded address: the first
 * comma-separated part that still has a word in it once a leading plus code is
 * stripped. Skips house numbers ("1", "4/1") and bare plus codes, which is
 * what the first segment is for most real addresses here.
 */
export function placeName(address: string | null): string | null {
    if (!address) return null;
    for (const raw of address.split(',')) {
        const part = raw.trim().replace(PLUS_CODE, '').trim();
        if (/[A-Za-z]{3,}/.test(part)) return part;
    }
    return null;
}

/**
 * What to call an incident in a list. Titles are written by Node in a later
 * phase and are null on every incident today, so the category alone would
 * make a ward's list read "Garbage, Garbage, Garbage". The place tells them
 * apart.
 */
export function incidentName(incident: Incident, categoryLabel: (code: string) => string): string {
    if (incident.title) return incident.title;
    const place = placeName(incident.address);
    return place ? `${categoryLabel(incident.category)} · ${place}` : categoryLabel(incident.category);
}

/**
 * A short incident list for the detail pages. Selecting a row opens that
 * incident in the queue (useOpenIncident).
 */
export default function IncidentList({ incidents, categoryLabel, caption, emptyMessage }: Props) {
    const openIncident = useOpenIncident();

    const columns = useMemo<Column<Incident>[]>(
        () => [
            {
                key: 'title',
                header: 'Incident',
                cell: (row) => truncate(incidentName(row, categoryLabel), 56),
                minWidth: '220px',
            },
            {
                key: 'priority',
                header: 'Priority',
                cell: (row) => <PriorityChip band={row.priorityBand} score={row.priorityScore} />,
                width: '140px',
            },
            {
                key: 'status',
                header: 'Status',
                cell: (row) => <StatusChip status={row.status} />,
                width: '120px',
            },
            {
                key: 'reports',
                header: 'Reports',
                cell: (row) => formatCount(row.complaintCount),
                align: 'end',
                width: '80px',
            },
            {
                key: 'last',
                header: 'Last report',
                cell: (row) => (row.lastReportedAt ? formatRelative(row.lastReportedAt) : '—'),
                align: 'end',
                width: '120px',
            },
        ],
        [categoryLabel]
    );

    return (
        <DataTable
            caption={caption}
            columns={columns}
            rows={incidents}
            rowKey={(row) => row.id}
            emptyMessage={emptyMessage}
            onSelect={(row) => openIncident(row.id, row.wardId)}
        />
    );
}
