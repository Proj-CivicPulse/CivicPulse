import { Router } from 'express';
import { z } from 'zod';
import { internalAuth } from '../middleware/internalAuth.ts';
import { answerQuestion } from '../services/copilot.service.ts';

export const copilotRoute = Router();

/**
 * Spring sends the officer's question and the grounding packet it built. The
 * packet is opaque here — its shape is Spring's (docs/api-contract.md,
 * "Copilot") — but it must be an object, and the question is bounded.
 */
const answerBody = z.strictObject({
    question: z.string().trim().min(1).max(1000),
    packet: z.record(z.string(), z.unknown()),
});

/**
 * POST /internal/copilot/answer — service-to-service only.
 *
 * There is deliberately no browser-facing Copilot route on this service: the
 * old stub took an `officer_id` from the request body, which on a service with
 * no sessions is whatever the caller typed. Officers call backend-spring's
 * POST /copilot/query, which checks the session and role, builds the packet,
 * and calls this.
 */
copilotRoute.post('/internal/copilot/answer', internalAuth, async (req, res) => {
    const { question, packet } = answerBody.parse(req.body ?? {});
    res.json(await answerQuestion(question, packet));
});
