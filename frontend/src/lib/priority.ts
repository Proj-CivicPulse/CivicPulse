/**
 * Priority band presentation.
 *
 * The band is decided by the SERVER and arrives as `Incident.priorityBand`.
 * The thresholds are part of the prioritisation model, and the model lives in
 * backend-spring (docs/service-boundaries.md decision 1). Three consumers need
 * the band — this dashboard, the Leaflet marker colour, and Node's Copilot —
 * and three copies of four numbers is three chances to disagree.
 *
 * `bandFromScore` exists only as a defensive fallback for rendering an incident
 * that somehow arrives without the field. It is not the source of truth.
 */

export type PriorityBand = 'low' | 'medium' | 'high' | 'critical';

export const PRIORITY_BANDS = {
    low: { label: 'Low', rank: 0 },
    medium: { label: 'Medium', rank: 1 },
    high: { label: 'High', rank: 2 },
    critical: { label: 'Critical', rank: 3 },
} as const;

export const PRIORITY_BAND_ORDER: readonly PriorityBand[] = [
    'critical',
    'high',
    'medium',
    'low',
];

/** Mirrors PriorityBand.of(double) in backend-spring. Keep the two in step. */
export function bandFromScore(score: number): PriorityBand {
    if (score >= 7.5) return 'critical';
    if (score >= 5.5) return 'high';
    if (score >= 3.0) return 'medium';
    return 'low';
}

export function priorityLabel(band: PriorityBand): string {
    return PRIORITY_BANDS[band].label;
}

/** Sorts most-urgent-first, for client-side reordering of an already-fetched set. */
export function comparePriority(a: PriorityBand, b: PriorityBand): number {
    return PRIORITY_BANDS[b].rank - PRIORITY_BANDS[a].rank;
}
