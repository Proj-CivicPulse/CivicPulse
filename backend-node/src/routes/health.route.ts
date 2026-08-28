import { Router } from 'express';
import { checkDatabase } from '../db/pool.ts';

export const healthRoute = Router();

/**
 * GET /health
 *
 * Reports real service health, not a hardcoded 200. Returns
 * 200 { status: 'ok' } only when Postgres answers `SELECT 1`; otherwise
 * 503 { status: 'error' }. Response shape matches
 * frontend/src/services/health.service.ts (`{ status: 'ok' | 'error' }`).
 */
healthRoute.get('/health', async (_req, res) => {
    const databaseOk = await checkDatabase();

    if (!databaseOk) {
        res.status(503).json({ status: 'error' });
        return;
    }

    res.json({ status: 'ok' });
});
