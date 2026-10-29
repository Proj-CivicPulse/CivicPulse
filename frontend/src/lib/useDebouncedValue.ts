import { useEffect, useState } from 'react';

/**
 * `value`, but only once it has stopped changing for `delayMs`.
 *
 * For search-as-you-type: each keystroke restarts the timer, so typing
 * "pothole" sends one request rather than seven, and the results for "pot"
 * never flash up on the way.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
    const [debounced, setDebounced] = useState(value);

    useEffect(() => {
        const id = window.setTimeout(() => setDebounced(value), delayMs);
        return () => window.clearTimeout(id);
    }, [value, delayMs]);

    return debounced;
}
