/**
 * The Explorer's "reported within" window, as the API's from/to instants.
 *
 * Every Explorer endpoint filters on when complaints were REPORTED
 * (docs/data-quality-rules.md §3), so the window means one thing everywhere.
 *
 * Bounds are whole UTC days — `to` is the start of tomorrow, so today counts —
 * which keeps the strings stable for a whole day. A `to` of "now" would change
 * on every render and turn each query key into a fresh fetch.
 */

export type WindowDays = 7 | 30 | 90;

export const WINDOW_OPTIONS: readonly { days: WindowDays | null; label: string }[] = [
    { days: null, label: 'All time' },
    { days: 7, label: '7 days' },
    { days: 30, label: '30 days' },
    { days: 90, label: '90 days' },
];

const DAY_MS = 24 * 60 * 60 * 1000;

function startOfTomorrowUtc(now: Date): Date {
    return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
}

/** from/to for a window; both undefined for "all time". */
export function reportWindow(
    days: WindowDays | null,
    now: Date = new Date(),
): { from?: string; to?: string } {
    if (days === null) return {};
    const to = startOfTomorrowUtc(now);
    return {
        from: new Date(to.getTime() - days * DAY_MS).toISOString(),
        to: to.toISOString(),
    };
}

/**
 * The range a trend chart draws. A trend needs both ends, so "all time" falls
 * back to the last 90 days — and says so in the chart heading — rather than
 * asking the server for a range it would refuse past 400 buckets.
 * Day buckets up to 90 days; week buckets beyond.
 */
export function trendRange(
    days: WindowDays | null,
    now: Date = new Date(),
): { from: string; to: string; bucket: 'day' | 'week'; label: string } {
    const effective = days ?? 90;
    const { from, to } = reportWindow(effective, now) as { from: string; to: string };
    return {
        from,
        to,
        bucket: effective <= 90 ? 'day' : 'week',
        label: days === null ? 'Last 90 days' : `Last ${effective} days`,
    };
}
