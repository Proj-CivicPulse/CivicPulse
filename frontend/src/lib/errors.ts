/**
 * Error copy.
 *
 * Errors state what happened and what to do. They don't apologise and they
 * aren't vague — "Something went wrong" tells the user nothing they can act on.
 *
 * `api.ts` already unwraps the shared `{ error: { code, message } }` envelope
 * into ApiError.message, so a server-authored message is usually the most
 * specific thing available and is preferred. These fall back to it, and only
 * override where the server's wording is generic or the condition is
 * client-side (network, timeout) and has no server message at all.
 */

import { ApiError } from '@/services/api';

interface ErrorCopy {
    title: string;
    detail: string;
}

export function describeError(error: unknown, context?: string): ErrorCopy {
    if (error instanceof ApiError) {
        switch (error.status) {
            // Thrown by api.ts when fetch itself rejects — the request never
            // reached a server, so there is no server message to show.
            case 0:
                return {
                    title: "Couldn't reach the server",
                    detail: 'Check your connection and try again.',
                };
            case 408:
                return {
                    title: 'That took too long',
                    detail: 'The server did not respond in time. Try again in a moment.',
                };
            case 401:
                return {
                    title: 'Your session has ended',
                    detail: 'Sign in again to continue.',
                };
            case 403:
                return {
                    title: 'You do not have access to this',
                    detail: 'This area is for ward officers.',
                };
            case 404:
                return {
                    title: 'Not found',
                    detail: context ? `We couldn't find that ${context}.` : "We couldn't find that.",
                };
            case 429:
                return {
                    title: 'Too many requests',
                    detail: 'Wait a minute, then try again.',
                };
            case 500:
            case 502:
            case 503:
                return {
                    title: 'The server is having trouble',
                    detail: 'This is on our side, not yours. Try again shortly.',
                };
            default:
                return {
                    title: context ? `Couldn't load ${context}` : "That didn't work",
                    detail: error.message,
                };
        }
    }

    return {
        title: context ? `Couldn't load ${context}` : "That didn't work",
        detail: 'Try again in a moment.',
    };
}

/** Single-line form, for inline form errors where a title+detail pair is too heavy. */
export function errorMessage(error: unknown, context?: string): string {
    const { title, detail } = describeError(error, context);
    return `${title}. ${detail}`;
}

/**
 * 501 is Node's honest answer for a Phase 2/6/7 stub, and its message already
 * names the phase. Worth distinguishing so the UI can say "not built yet"
 * rather than "something failed".
 */
export function isNotImplemented(error: unknown): boolean {
    return error instanceof ApiError && error.status === 501;
}
