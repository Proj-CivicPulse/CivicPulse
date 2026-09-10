// Single source of truth for the project name and API paths.
// Change VITE_APP_NAME in .env.local to rename the whole app — nothing
// else in the codebase should hardcode "CivicPulse".

export const APP_NAME: string = import.meta.env.VITE_APP_NAME ?? 'CivicPulse';

// Base for every API call: either a relative path proxied by Vite, or an
// absolute origin.
//
// DEV (`vite dev`): VITE_API_*_URL are unset, so these stay the relative
// '/api/core' and '/api/ai' prefixes and the dev-server proxy in
// vite.config.ts rewrites and forwards them. Nothing about local development
// changes.
//
// PROD (Vercel): there is no proxy — the bundle is static files on a CDN and
// each backend is on its own Railway domain — so set VITE_API_CORE_URL and
// VITE_API_AI_URL to those origins. Vite inlines them at BUILD time, which
// means changing either in the Vercel dashboard requires a redeploy, not just
// a page reload.
//
// Callers always pass a leading-slash path ('/auth/login'), so a trailing
// slash on the env value is stripped to avoid a '//auth/login' request.
const stripTrailingSlash = (url: string): string => url.replace(/\/+$/, '');

const apiBase = (envUrl: string | undefined, proxyPath: string): string =>
    envUrl && envUrl.length > 0 ? stripTrailingSlash(envUrl) : proxyPath;

export const SPRING_API_PATH: string = apiBase(
    import.meta.env.VITE_API_CORE_URL,
    '/api/core',
);
export const NODE_API_PATH: string = apiBase(import.meta.env.VITE_API_AI_URL, '/api/ai');

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
