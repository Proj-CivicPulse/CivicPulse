/**
 * Display formatting.
 *
 * Numbers that sit in a column or update in place must render with tabular
 * figures so digits don't shift horizontally between renders — apply the
 * `.tabular` class from index.css, or `font-variant-numeric: tabular-nums`.
 */

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
