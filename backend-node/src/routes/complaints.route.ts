import { Router } from 'express';
import { z } from 'zod';
import { notImplemented } from '../middleware/errorHandler.ts';

export const complaintsRoute = Router();

const processParams = z.object({
    id: z.string().min(1, 'complaint id is required'),
});

// No request body is defined for this endpoint yet — the complaint id in
// the path is the only input. Reject any body outright until Phase 2
// settles the contract (see docs/api-contract.md).
const processBody = z.strictObject({});

/**
 * POST /complaints/:id/process  — Phase 2 (stub)
 *
 * Will: fetch full complaint context from Spring (GET /internal/complaints/:id),
 * generate an embedding (services/embedding.service), run candidate
 * filtering + similarity matching (services/matching.service), then attach
 * the complaint to a new or existing incident via Spring
 * (POST /internal/incidents/attach).
 *
 * Note the route name: not /internal/incidents/:id/complaints, because
 * incidentId may be null ("start a new incident") and a null cannot occupy a
 * path segment. docs/endpoints.md has been corrected to match.
 *
 * Every /internal/* call must send the X-Internal-Token header; Spring denies
 * the whole prefix when its INTERNAL_TOKEN is unset.
 *
 * Service-boundary decisions are now settled — see docs/service-boundaries.md:
 * Spring owns the Incident write and is called synchronously, no queue. What
 * remains for this route is the embedding and similarity work itself.
 */
complaintsRoute.post('/complaints/:id/process', (req, _res) => {
    processParams.parse(req.params);
    // req.body is undefined when no JSON body is sent — treat that as {}.
    processBody.parse(req.body ?? {});
    throw notImplemented(
        'Complaint processing (embedding generation + incident matching) is not implemented yet — Phase 2.',
    );
});
