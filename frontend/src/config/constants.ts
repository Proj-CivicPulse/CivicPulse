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

// CARTO basemap key. Optional: without it the tiles still render but carry an
// "API KEY REQUIRED" watermark, which is CARTO's nag rather than a hard block.
//
// This is a PUBLIC key by nature — Vite inlines every VITE_* var into the
// bundle, and a tile key has to travel with the browser's image requests
// regardless. Restrict it by domain in the CARTO dashboard; that is the only
// control that means anything here. Never put a server-side secret (the Google
// Geocoding key) behind a VITE_ prefix.
export const MAP_TILE_API_KEY: string | undefined =
    import.meta.env.VITE_CARTO_API_KEY || undefined;
