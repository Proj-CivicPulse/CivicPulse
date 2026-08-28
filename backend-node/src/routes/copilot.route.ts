import { Router } from 'express';
import { z } from 'zod';
import { notImplemented } from '../middleware/errorHandler.ts';

export const copilotRoute = Router();

// Body shape per docs/api-contract.md (POST /copilot/query). Enforced now
// so a malformed request is rejected with 400 VALIDATION_ERROR before it
// reaches any handler logic, even though the handler is still a stub.
const copilotQueryBody = z.strictObject({
    officer_id: z.string().min(1),
    query: z.string().min(1).max(2000),
    ward_id: z.string().min(1).optional(),
});

/**
 * POST /copilot/query  — Phase 6 (stub)
 *
 * Will: classify intent, retrieve grounding data from CivicPulse's own
 * store (never the open web), call an external LLM with that context
 * injected (services/copilot.service), and return { answer, sources }.
 *
 * TODO (Phase 6): mount a dedicated strict rate limiter on this route —
 * see src/middleware/rateLimiter.ts.
 */
copilotRoute.post('/copilot/query', (req, _res) => {
    copilotQueryBody.parse(req.body ?? {});
    throw notImplemented('The officer Copilot is not implemented yet — Phase 6.');
});
