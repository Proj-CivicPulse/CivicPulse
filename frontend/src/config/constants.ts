// Single source of truth for the project name and API paths.
// Change VITE_APP_NAME in .env.local to rename the whole app — nothing
// else in the codebase should hardcode "CivicPulse".

export const APP_NAME: string = import.meta.env.VITE_APP_NAME ?? 'CivicPulse';

// Relative paths, proxied by Vite in dev (see vite.config.ts) and by the
// production reverse proxy later. Frontend code should never hardcode a
// host/port — that keeps dev and prod requests identical.
export const SPRING_API_PATH = '/api/core';
export const NODE_API_PATH = '/api/ai';

export const REQUEST_TIMEOUT_MS: number = Number(
    import.meta.env.VITE_REQUEST_TIMEOUT_MS ?? 10000
);