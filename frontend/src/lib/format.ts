/**
 * Display formatting.
 *
 * Numbers that sit in a column or update in place must render with tabular
 * figures so digits don't shift horizontally between renders — apply the
 * `.tabular` class from index.css, or `font-variant-numeric: tabular-nums`.
 */

import type { MatchingStatus } from '@/services/complaint.service';

const numberFormat = new Intl.NumberFormat('en-IN');

export function formatCount(value: number): string {
    return numberFormat.format(value);
}

/** e.g. "12.9716, 77.5946" — six decimals is ~11cm, past which it's noise. */
export function formatCoordinates(lat: number, long: number): string {
    return `${lat.toFixed(6)}, ${long.toFixed(6)}`;
}

/** One decimal, always shown, so 7 and 7.4 align in the priority column. */
export function formatScore(score: number): string {
    return score.toFixed(1);
}

/**
 * "Grouped with 11 other reports" — the count EXCLUDES the citizen's own
 * report, because `complaintCount` on the incident includes it and "grouped
 * with 12 others" when 12 is the total would overcount by one.
 */
export function formatGroupedWith(complaintCount: number): string | null {
    const others = complaintCount - 1;
    if (others < 1) return null;
    return others === 1
        ? 'Grouped with 1 other report'
        : `Grouped with ${formatCount(others)} other reports`;
}

/**
 * What a resident is told about grouping, given how far matching has actually
 * got. Returns null when there is nothing worth saying.
 *
 * `settled` is false while the grouping can still change. Callers must
 * de-emphasise those: a provisional grouping rendered with the same weight as a
 * final one tells someone their report joined five others and then quietly
 * changes its mind, which costs more trust than saying nothing would have.
 */
export interface MatchingSummary {
    text: string;
    settled: boolean;
}

export function formatMatchingSummary(
    status: MatchingStatus,
    complaintCount: number | null,
): MatchingSummary | null {
    const grouped = complaintCount !== null ? formatGroupedWith(complaintCount) : null;

    switch (status) {
        // No verdict yet. Say so, because rendering nothing here is not neutral
        // -- it reads as "your report stands alone", which is the one thing the
        // matcher has not yet established.
        case 'pending':
        case 'processing':
            return { text: 'Checking for similar reports…', settled: false };

        // Grouped by the naive fallback (ward + category + window + radius)
        // because the matching service was unreachable. The reconcile sweep
        // re-runs the real pipeline and may move this report to a different
        // incident, so it must never be presented as final.
        case 'degraded':
            if (grouped === null) {
                return { text: 'Checking for similar reports…', settled: false };
            }
            return {
                text: `Provisionally ${grouped.charAt(0).toLowerCase()}${grouped.slice(1)}`,
                settled: false,
            };

        // Settled. Alone in its incident is a real answer, but not one worth a
        // line of its own -- the absence of a grouping line already says it.
        case 'matched':
            return grouped === null ? null : { text: grouped, settled: true };
    }
}

/** Collapses whitespace and clips to a single line for dense list rows. */
export function truncate(text: string, max: number): string {
    const collapsed = text.replace(/\s+/g, ' ').trim();
    if (collapsed.length <= max) return collapsed;
    return `${collapsed.slice(0, max - 1).trimEnd()}…`;
}

/** Turns a wire enum ("in_progress") into display copy ("In progress"). */
export function humanizeEnum(value: string): string {
    const spaced = value.replace(/_/g, ' ');
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}
