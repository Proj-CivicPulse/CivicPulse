import { Router } from 'express';
import { z } from 'zod';
import { notImplemented } from '../middleware/errorHandler.ts';

export const hotspotsRoute = Router();

// Optional ward filter on the query string. Non-strict: query strings pick
// up incidental params, and this is the lowest-priority Phase 7 endpoint.
const predictQuery = z.object({
    ward_id: z.string().min(1).optional(),
});

/**
 * GET /hotspots/predict  — Phase 7 (stub, conditional)
 *
 * Only built if data volume / timestamp quality / coordinate reliability
 * clear the gate in docs/roadmap.md. Would proxy to an isolated Python
 * service (ml-hotspots) and return predicted hotspot areas. Do not build
 * ahead of that decision.
 */
hotspotsRoute.get('/hotspots/predict', (req, _res) => {
    predictQuery.parse(req.query ?? {});
    throw notImplemented('Predictive hotspots are not implemented yet — Phase 7 (conditional).');
});
