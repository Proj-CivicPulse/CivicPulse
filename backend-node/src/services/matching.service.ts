/**
 * matching.service — Phase 2
 *
 * Given a new complaint's embedding, decide whether it joins an existing
 * incident or starts a new one.
 *
 * Scoring is single-linkage: an incident's score is the MAX cosine similarity
 * to any one of its member complaints. Chaining risk is real (one loose match
 * acts as a magnet) but it is the choice with the best explainability story —
 * "matched complaint #4821 at 0.89" is something an officer can click into and
 * verify — and it does not decay as a long-running incident broadens. The
 * mitigation is threshold tuning + the officer override, both already planned.
 *
 * Candidate filtering (ward + category + active status) runs first, in SQL, so
 * the vector maths only ever touches a handful of rows.
 *
 * Spring performs the write; this service only decides. See
 * docs/service-boundaries.md.
 */

import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';
import { pool } from '../db/pool.ts';
import type { SpringComplaint } from './spring.client.ts';

// Incident status is stored uppercase (@Enumerated(STRING) on the Spring entity).
const CANDIDATES_SQL = `
    SELECT i.id::text                                                AS incident_id,
           MAX(1 - (c.embedding <=> $1::vector))                     AS top_similarity,
           (ARRAY_AGG(c.id::text ORDER BY c.embedding <=> $1::vector))[1] AS top_sibling_id,
           COUNT(*)::int                                             AS member_count
    FROM incidents i
    JOIN complaints c ON c.incident_id = i.id
    WHERE i.ward_id = $2
      AND i.category = $3
      AND i.status IN ('OPEN', 'IN_PROGRESS')
      AND c.embedding IS NOT NULL
      AND c.id <> $4
    GROUP BY i.id
    ORDER BY top_similarity DESC
    LIMIT 20
`;

interface CandidateRow {
    incident_id: string;
    top_similarity: number;
    top_sibling_id: string;
    member_count: number;
}

export interface MatchDecision {
    /** null => start a new incident. */
    incidentId: string | null;
    topSimilarity: number | null;
    topSiblingComplaintId: string | null;
    candidateCount: number;
    threshold: number;
}

function toLiteral(vector: number[]): string {
    return `[${vector.join(',')}]`;
}

export async function findCandidates(
    complaint: SpringComplaint,
    vector: number[],
): Promise<CandidateRow[]> {
    const result = await pool.query<CandidateRow>(CANDIDATES_SQL, [
        toLiteral(vector),
        complaint.wardId,
        complaint.category,
        complaint.id,
    ]);
    return result.rows;
}

export async function matchOrCreate(
    complaint: SpringComplaint,
    vector: number[],
): Promise<MatchDecision> {
    const candidates = await findCandidates(complaint, vector);
    const threshold = env.MATCH_SIMILARITY_THRESHOLD;
    const best = candidates[0];

    // Log every candidate score, above or below threshold — Phase 8's F1
    // evaluation needs the raw numbers, not just the outcome.
    logger.info(
        {
            complaintId: complaint.id,
            wardId: complaint.wardId,
            category: complaint.category,
            threshold,
            candidates: candidates.map((c) => ({
                incidentId: c.incident_id,
                topSimilarity: c.top_similarity,
                topSiblingId: c.top_sibling_id,
                memberCount: c.member_count,
            })),
        },
        'incident match candidates',
    );

    if (best && best.top_similarity >= threshold) {
        return {
            incidentId: best.incident_id,
            topSimilarity: best.top_similarity,
            topSiblingComplaintId: best.top_sibling_id,
            candidateCount: candidates.length,
            threshold,
        };
    }

    return {
        incidentId: null,
        topSimilarity: best ? best.top_similarity : null,
        topSiblingComplaintId: best ? best.top_sibling_id : null,
        candidateCount: candidates.length,
        threshold,
    };
}
