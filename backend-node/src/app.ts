import express from 'express';
import type { Express } from 'express';
import helmet from 'helmet';
import cors from 'cors';
import { pinoHttp } from 'pino-http';
import { env } from './config/env.ts';
import { logger } from './config/logger.ts';
import { generalLimiter } from './middleware/rateLimiter.ts';
import { errorHandler, notFoundHandler } from './middleware/errorHandler.ts';
import { healthRoute } from './routes/health.route.ts';
import { complaintsRoute } from './routes/complaints.route.ts';
import { copilotRoute } from './routes/copilot.route.ts';
import { hotspotsRoute } from './routes/hotspots.route.ts';

/**
 * Builds the Express app with all middleware and routes wired up, but does
 * NOT start listening — that's index.ts's job. Keeping construction
 * separate makes the app importable by tests without opening a port.
 */
export function createApp(): Express {
    const app = express();

    // Sits behind the Vite dev proxy (and nginx in prod) — trust exactly
    // one hop so req.ip and the rate limiter see the real client address,
    // not the proxy's.
    app.set('trust proxy', 1);
    app.disable('x-powered-by');

    // Security headers. contentSecurityPolicy is disabled deliberately:
    // this service only ever returns JSON, so a CSP header is pure overhead
    // with nothing to protect.
    app.use(helmet({ contentSecurityPolicy: false }));

    // Exactly one allowed browser origin, from env. Never '*' — credentials
    // are allowed, and '*' + credentials is both invalid and unsafe.
    app.use(cors({ origin: env.FRONTEND_ORIGIN, credentials: true }));

    // Bound the request body. 100kb comfortably covers complaint text and
    // Copilot queries while cutting off oversized-payload abuse.
    app.use(express.json({ limit: '100kb' }));

    // Structured request logging via pino (non-blocking). The health check
    // is polled constantly — don't log it.
    app.use(
        pinoHttp({
            logger,
            autoLogging: { ignore: (req) => req.url === '/health' },
            // 501 is an expected state for the Phase 6/7 stubs, not a
            // server fault — keep it out of the error stream.
            customLogLevel: (_req, res, err) => {
                if (err) return 'error';
                if (res.statusCode === 501) return 'warn';
                if (res.statusCode >= 500) return 'error';
                if (res.statusCode >= 400) return 'warn';
                return 'info';
            },
            serializers: {
                // pino-http synthesizes an Error for every response >= 500,
                // including the deliberate 501 stubs. Drop that synthetic
                // noise; real thrown errors (with a genuine stack) still log.
                err: (err: { message?: string } & Record<string, unknown>) =>
                    typeof err?.message === 'string' && err.message.startsWith('failed with status code')
                        ? undefined
                        : err,
            },
        }),
    );

    // General per-IP rate limit across the API (health check is exempted
    // inside the limiter).
    app.use(generalLimiter);

    // Routes — all prefix-free. The /api/ai prefix is added by the frontend
    // proxy only (see frontend/vite.config.ts) and must not appear here.
    app.use(healthRoute);
    app.use(complaintsRoute);
    app.use(copilotRoute);
    app.use(hotspotsRoute);

    // 404 for anything unmatched, then the centralized error handler.
    // Order matters: both must come last.
    app.use(notFoundHandler);
    app.use(errorHandler);

    return app;
}
