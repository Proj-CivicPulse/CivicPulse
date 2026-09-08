import type { Request } from 'express';
import { rateLimit } from 'express-rate-limit';

/** `POST /complaints/:id/process` — the service-to-service matching trigger. */
const INTERNAL_PROCESS_PATH = /^\/complaints\/[^/]+\/process$/;

/**
 * Routes the public per-IP budget must not apply to.
 *
 * - `/health` is polled continuously by the dashboard and by uptime monitors;
 *   rate-limiting it would mask real outages.
 * - `/complaints/:id/process` is called by backend-spring, not by a browser.
 *   It is gated by `internalAuth` (constant-time `X-Internal-Token` compare),
 *   which is its real access control — the IP budget adds no protection there
 *   and actively breaks the integration: a reconcile backlog runs at 25 calls
 *   a minute, so it burns 100 requests in four minutes, starts getting 429s,
 *   and Spring reads those as "Node is down" and opens its circuit breaker.
 *   The service then rate-limits itself into a self-inflicted outage, and the
 *   harder it tries to recover, the worse it gets. An unauthenticated caller
 *   still gets a 401 here before any work is done.
 */
function isExempt(req: Request): boolean {
    if (req.method === 'GET' && req.path === '/health') return true;
    return req.method === 'POST' && INTERNAL_PROCESS_PATH.test(req.path);
}

/**
 * General limiter applied to the whole API surface. `trust proxy` is set
 * to 1 in app.ts so this keys on the real client IP, not the Vite/nginx
 * proxy's address.
 *
 * TODO (Phase 6): POST /copilot/query needs its OWN, much stricter limiter
 * mounted on that route — each call fans out to an external LLM, which is
 * slow and metered. The general budget here is far too generous for it.
 */
export const generalLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    limit: 100, // per IP per window
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    skip: isExempt,
    handler: (_req, res) => {
        res.status(429).json({
            error: { code: 'RATE_LIMITED', message: 'Too many requests, please try again later' },
        });
    },
});
