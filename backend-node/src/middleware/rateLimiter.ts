import { rateLimit } from 'express-rate-limit';

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
    // The health check is polled continuously by the dashboard and by
    // uptime monitors — rate-limiting it would mask real outages.
    skip: (req) => req.method === 'GET' && req.path === '/health',
    handler: (_req, res) => {
        res.status(429).json({
            error: { code: 'RATE_LIMITED', message: 'Too many requests, please try again later' },
        });
    },
});
