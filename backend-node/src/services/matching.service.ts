/**
 * matching.service — Phase 2 (not implemented)
 *
 * Responsibility: given a new complaint's embedding, decide whether it
 * joins an existing incident or starts a new one.
 *
 * Planned shape:
 *   findCandidateIncidents(complaint): Promise<Incident[]>
 *     → cheap pre-filter FIRST (same ward + category + active status) via a
 *       parameterized query, before any vector math:
 *         SELECT ... FROM incident
 *         WHERE ward_id = $1 AND category = $2 AND status = 'active'
 *   scoreSimilarity(candidate, embedding): number
 *     → pgvector distance (<=> operator) against the candidate set only
 *   matchOrCreate(complaint): Promise<{ incidentId: string; created: boolean }>
 *     → threshold is empirical; log every score for Phase 8 evaluation
 *
 * Writes to the Incident table go through Spring per the (still open)
 * decision in docs/service-boundaries.md — this service must not write it
 * directly until that's resolved.
 */

export {};
