/**
 * A duration in hours, in the unit an officer would say it in: hours under
 * two days, days beyond. One decimal only where it changes the reading.
 */
export function formatHours(hours: number): string {
    if (!Number.isFinite(hours) || hours < 0) return '—';
    if (hours < 1) return 'Under 1 h';
    if (hours < 48) return `${hours < 10 ? hours.toFixed(1) : Math.round(hours)} h`;
    const days = hours / 24;
    return `${days < 10 ? days.toFixed(1) : Math.round(days)} days`;
}
