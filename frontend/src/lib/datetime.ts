/**
 * Server timestamp handling.
 *
 * backend-spring stores timestamps in zoneless TIMESTAMP(6) columns mapped to
 * LocalDateTime. Jackson serialises those WITHOUT a trailing Z, and
 * `new Date('2026-08-29T10:15:00')` is parsed by the browser as LOCAL time —
 * which shifts every timestamp by the viewer's UTC offset (5h30m in IST).
 *
 * The backend is being fixed to emit ISO-8601 with an explicit Z. Until every
 * endpoint does, `parseServerDate` treats an offset-less string as UTC, which
 * is what the column actually holds. Both forms therefore land on the same
 * instant, and this stays correct after the backend fix.
 */

const OFFSETLESS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;

export function parseServerDate(value: string): Date | null {
    if (!value) return null;
    const normalized = OFFSETLESS.test(value) ? `${value}Z` : value;
    const date = new Date(normalized);
    return Number.isNaN(date.getTime()) ? null : date;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Relative time in the register's voice: plain, sentence case, no "about".
 * Falls back to an absolute date beyond a month, where "5 weeks ago" stops
 * being more useful than the date itself.
 */
export function formatRelative(value: string, now: Date = new Date()): string {
    const date = parseServerDate(value);
    if (!date) return '—';

    const elapsed = now.getTime() - date.getTime();
    if (elapsed < 0) return formatAbsolute(value);
    if (elapsed < MINUTE) return 'Just now';

    if (elapsed < HOUR) {
        const mins = Math.floor(elapsed / MINUTE);
        return `${mins} ${plural(mins, 'minute')} ago`;
    }
    if (elapsed < DAY) {
        const hours = Math.floor(elapsed / HOUR);
        return `${hours} ${plural(hours, 'hour')} ago`;
    }
    if (elapsed < 30 * DAY) {
        const days = Math.floor(elapsed / DAY);
        return days === 1 ? 'Yesterday' : `${days} days ago`;
    }
    return formatAbsolute(value);
}

/** e.g. "29 Aug 2026". Used in tables and anywhere precision beats recency. */
export function formatAbsolute(value: string): string {
    const date = parseServerDate(value);
    if (!date) return '—';
    return new Intl.DateTimeFormat('en-IN', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
    }).format(date);
}

/** Whole days since `value`, for the officer queue's Age column. */
export function daysSince(value: string, now: Date = new Date()): number | null {
    const date = parseServerDate(value);
    if (!date) return null;
    return Math.max(0, Math.floor((now.getTime() - date.getTime()) / DAY));
}

function plural(count: number, word: string): string {
    return count === 1 ? word : `${word}s`;
}
