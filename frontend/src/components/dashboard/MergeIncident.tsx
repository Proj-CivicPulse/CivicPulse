import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { incidentService, type Incident } from '@/services/incident.service';
import { queryKeys } from '@/lib/queryKeys';
import { describeError } from '@/lib/errors';
import { formatCount } from '@/lib/format';
import { useDashboardStore } from '@/stores/dashboard.store';
import Button from '@/components/ui/Button';
import Field from '@/components/ui/Field';
import { incidentName } from '@/components/explorer/IncidentList';
import styles from './UpdateIncident.module.css';

/** Metres between two points; close enough at ward scale. */
function metresBetween(a: Incident, b: Incident): number | null {
    if (a.lat === null || a.long === null || b.lat === null || b.long === null) return null;
    const rad = Math.PI / 180;
    const x = (b.long - a.long) * rad * Math.cos(((a.lat + b.lat) / 2) * rad);
    const y = (b.lat - a.lat) * rad;
    return Math.round(Math.sqrt(x * x + y * y) * 6_371_000);
}

function distanceLabel(m: number | null): string {
    if (m === null) return 'distance unknown';
    return m < 1000 ? `${Math.round(m / 10) * 10} m away` : `${(m / 1000).toFixed(1)} km away`;
}

/**
 * The officer's override for a split the matcher made: fold this incident
 * into another that is the same problem. Only incidents the server would
 * accept are offered — active, same ward, same category — nearest first.
 */
export default function MergeIncident({ incident, categoryLabel }: {
    incident: Incident;
    categoryLabel: (code: string) => string;
}) {
    const queryClient = useQueryClient();
    const selectIncident = useDashboardStore((s) => s.selectIncident);
    const [target, setTarget] = useState('');
    const [confirming, setConfirming] = useState(false);

    const active = incident.status === 'open' || incident.status === 'in_progress';
    const filters = {
        wardIds: [incident.wardId],
        categories: [incident.category],
        statuses: ['open', 'in_progress'] as Incident['status'][],
        limit: 200,
    };
    const candidates = useQuery({
        queryKey: queryKeys.incidents.list(filters),
        queryFn: () => incidentService.list(filters),
        enabled: active,
    });

    const merge = useMutation({
        mutationFn: () => incidentService.merge(incident.id, target),
        onSuccess: (data) => {
            // This incident no longer exists; the one it became is the one to look at.
            selectIncident(data.target.id);
            // Its own panel queries would only 404 now: drop them instead of refetching.
            const gone = queryKeys.incidents.detail(incident.id);
            void queryClient.invalidateQueries({
                queryKey: queryKeys.incidents.all(),
                predicate: (q) => !gone.every((part, i) => q.queryKey[i] === part),
            });
            queryClient.removeQueries({ queryKey: gone });
            void queryClient.invalidateQueries({ queryKey: queryKeys.dashboard.all() });
        },
    });

    if (!active) return null;
    const options = (candidates.data?.items ?? [])
        .filter((c) => c.id !== incident.id)
        .map((c) => ({ incident: c, metres: metresBetween(incident, c) }))
        .sort((a, b) => (a.metres ?? Infinity) - (b.metres ?? Infinity));
    if (candidates.isSuccess && options.length === 0) return null;

    const chosen = options.find((o) => o.incident.id === target);

    return (
        <section className={styles.box} aria-labelledby={`merge-${incident.id}`}>
            <h3 className={styles.heading} id={`merge-${incident.id}`}>
                Same problem as another incident?
            </h3>
            <form
                className={styles.form}
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!target) return;
                    if (!confirming) setConfirming(true);
                    else merge.mutate();
                }}
            >
                <Field
                    as="select"
                    label="Merge into"
                    helper="Open incidents of the same category in this ward, nearest first."
                    value={target}
                    disabled={candidates.isPending}
                    onChange={(e) => {
                        setTarget(e.target.value);
                        setConfirming(false);
                    }}
                >
                    <option value="">Choose…</option>
                    {options.map(({ incident: c, metres }) => (
                        <option key={c.id} value={c.id}>
                            {`${incidentName(c, categoryLabel)} · ${distanceLabel(metres)} · ${formatCount(c.complaintCount)} ${c.complaintCount === 1 ? 'report' : 'reports'}`}
                        </option>
                    ))}
                </Field>
                {confirming && chosen && (
                    <p className={styles.heading}>
                        {incident.complaintCount === 1
                            ? 'Its report moves'
                            : `All ${formatCount(incident.complaintCount)} reports move`}{' '}
                        to “
                        {incidentName(chosen.incident, categoryLabel)}”, and this incident is removed.
                    </p>
                )}
                <div className={styles.buttons}>
                    <Button type="submit" size="compact" variant="secondary" disabled={!target || merge.isPending}>
                        {merge.isPending ? 'Merging…' : confirming ? 'Yes, merge' : 'Merge…'}
                    </Button>
                    {confirming && (
                        <Button type="button" size="compact" variant="quiet" onClick={() => setConfirming(false)}>
                            Cancel
                        </Button>
                    )}
                </div>
                {merge.isError && <p className={styles.error}>{describeError(merge.error, 'the merge').title}</p>}
            </form>
        </section>
    );
}
