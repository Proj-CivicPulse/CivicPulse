import { Router } from 'express';
import { z } from 'zod';
import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';
import { internalAuth } from '../middleware/internalAuth.ts';
import { claim, release } from '../services/claim.ts';
import { springClient } from '../services/spring.client.ts';
import {
    buildEmbeddingText,
    generateEmbedding,
    readEmbedding,
    storeEmbedding,
} from '../services/embedding.service.ts';
import { matchOrCreate } from '../services/matching.service.ts';

export const complaintsRoute = Router();

const processParams = z.object({
    id: z.string().min(1, 'complaint id is required'),
});

// The complaint id in the path is the only real input. Reject any body.
const processBody = z.strictObject({});

const isTruthyFlag = (value: unknown): boolean => value === 'true' || value === '1';

/**
 * POST /complaints/:id/process  — Phase 2
 *
 * backend-spring calls this after creating a complaint (asynchronously, so the
 * citizen never waits) and again from its reconcile job. The flow:
 *
 *   1. Atomic CAS claim -> PROCESSING. If another run owns it, exit `skipped`.
 *   2. Fetch complaint context from Spring.
 *   3. Generate (or reuse) the embedding, persist it.
 *   4. Candidate pre-filter + single-linkage similarity -> join or create.
 *   5. Hand the decision to Spring, which performs the write.
 *
 * ?force=true   also claims a MATCHED complaint / a stale PROCESSING one.
 * ?reembed=true regenerates the vector even if one is stored.
 *
 * Requires X-Internal-Token (internalAuth), like Spring's own /internal/*.
 */
complaintsRoute.post('/complaints/:id/process', internalAuth, async (req, res) => {
    const { id } = processParams.parse(req.params);
    processBody.parse(req.body ?? {});
    const force = isTruthyFlag(req.query.force);
    const reembed = isTruthyFlag(req.query.reembed);

    const { claimed, previousStatus } = await claim(id, { force });
    if (!claimed) {
        logger.info(
            { complaintId: id, force },
            'process skipped — another run holds the claim, or the complaint is already settled',
        );
        res.status(200).json({ complaintId: id, skipped: true });
        return;
    }

    try {
        const complaint = await springClient.getComplaint(id);

        let vector = reembed ? null : await readEmbedding(id);
        if (!vector) {
            vector = await generateEmbedding(
                buildEmbeddingText({
                    category: complaint.category,
                    title: complaint.title,
                    description: complaint.description,
                }),
            );
            await storeEmbedding(id, vector);
        }

        const decision = await matchOrCreate(complaint, vector);

        const result = await springClient.attach({
            complaintId: id,
            incidentId: decision.incidentId,
            topSimilarity: decision.topSimilarity,
            topSiblingComplaintId: decision.topSiblingComplaintId,
            candidateCount: decision.candidateCount,
            threshold: decision.threshold,
            model: env.EMBEDDING_MODEL,
            embeddingDim: env.EMBEDDING_DIMENSIONS,
        });

        logger.info(
            {
                complaintId: id,
                incidentId: result.incidentId,
                created: result.created,
                similarity: decision.topSimilarity,
            },
            'complaint processed',
        );

        res.status(202).json({
            complaintId: id,
            incidentId: result.incidentId,
            created: result.created,
            similarity: decision.topSimilarity,
            matcher: 'semantic',
        });
    } catch (err) {
        // A failed run must not strand the row in PROCESSING. Restore its prior
        // status so the reconcile job retries it promptly; the stale-takeover
        // window is only a backstop for a crash that never reaches here.
        if (previousStatus) {
            await release(id, previousStatus).catch((releaseErr) => {
                logger.error(
                    { err: releaseErr, complaintId: id },
                    'failed to release matching claim after an error',
                );
            });
        }
        throw err;
    }
});
