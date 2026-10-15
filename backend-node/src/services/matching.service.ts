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
 * the vector maths only ever touches a handful of rows. Since the category
 * registry those categories are canonical codes, so equivalent spellings share
 * one candidate pool instead of fragmenting into several.
 *
 * DISTANCE IS A POST-HOC CHECK, NOT A PRE-FILTER (since the Phase 7
 * evaluation, docs/matching-evaluation.md). The candidate query still ranks
 * every incident in the ward by similarity alone, and every score is still
 * logged — then a candidate is accepted only if its most similar member lies
 * within MATCH_MAX_SIBLING_DISTANCE_M of the new complaint. Done after ranking,
 * so the log keeps recording what similarity alone would have decided, which
 * is what the next evaluation needs. Measured on labelled production joins:
 * the check removed 6 of 22 wrong joins and lost none of 27 right ones.
 *
 * Time is still NOT constrained: an incident open for months is as eligible as
 * one opened this morning. The Java fallback keeps its own 14-day / 1.5 km
 * bounds because it has no similarity signal; the two are intentionally NOT
 * aligned. docs/matching-contract.md states all of this.
 *
 * Spring performs the write; this service only decides. See
 * docs/service-boundaries.md.
 */

import { env } from '../config/env.ts';
import { logger } from '../config/logger.ts';
import { pool } from '../db/pool.ts';
import type { SpringComplaint } from './spring.client.ts';

// Incident status is stored uppercase (@Enumerated(STRING) on the Spring entity).
//
// top_sibling_distance_m is the great-circle distance (haversine, metres) from
// the new complaint to that same most-similar member — null when either has no
// coordinates, in which case the distance check cannot apply and does not.
const CANDIDATES_SQL = `
    WITH me AS (SELECT latitude, longitude FROM complaints WHERE id = $4)
    SELECT i.id::text                                                AS incident_id,
           MAX(1 - (c.embedding <=> $1::vector))                     AS top_similarity,
           (ARRAY_AGG(c.id::text ORDER BY c.embedding <=> $1::vector))[1] AS top_sibling_id,
           (ARRAY_AGG(
                2 * 6371000 * ASIN(SQRT(
                    POWER(SIN(RADIANS(c.latitude - me.latitude) / 2), 2) +
                    COS(RADIANS(me.latitude)) * COS(RADIANS(c.latitude)) *
                    POWER(SIN(RADIANS(c.longitude - me.longitude) / 2), 2)))
                ORDER BY c.embedding <=> $1::vector))[1]             AS top_sibling_distance_m,
           COUNT(*)::int                                             AS member_count
    FROM incidents i
    JOIN complaints c ON c.incident_id = i.id
    LEFT JOIN me ON TRUE
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
    /** Metres to the most similar member; null when coordinates are missing. */
    top_sibling_distance_m: number | null;
    member_count: number;
}

export interface MatchDecision {
    /** null => start a new incident. */
    incidentId: string | null;
    topSimilarity: number | null;
    topSiblingComplaintId: string | null;
    candidateCount: number;
    threshold: number;
    /**
     * Candidates that cleared the threshold but were refused by the distance
     * check. A CREATED decision whose topSimilarity clears the threshold is
     * explained by this being > 0.
     */
    refusedByDistance: number;
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
    const maxDistance = env.MATCH_MAX_SIBLING_DISTANCE_M;
    const best = candidates[0];

    const tooFar = (c: CandidateRow): boolean =>
        maxDistance > 0 && c.top_sibling_distance_m !== null && Number(c.top_sibling_distance_m) > maxDistance;

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
                topSiblingDistanceM: c.top_sibling_distance_m,
                memberCount: c.member_count,
            })),
        },
        'incident match candidates',
    );

    // Candidates arrive best-first. The first one that clears the threshold
    // AND passes the distance check wins; one that clears the threshold but is
    // too far is skipped, not fatal — a nearer incident below it may still fit.
    let refusedByDistance = 0;
    for (const candidate of candidates) {
        if (candidate.top_similarity < threshold) break;
        if (tooFar(candidate)) {
            refusedByDistance++;
            logger.info(
                {
                    complaintId: complaint.id,
                    incidentId: candidate.incident_id,
                    similarity: candidate.top_similarity,
                    distanceM: candidate.top_sibling_distance_m,
                    maxDistanceM: maxDistance,
                },
                'semantic candidate refused: most similar member too far away',
            );
            continue;
        }
        return {
            incidentId: candidate.incident_id,
            topSimilarity: candidate.top_similarity,
            topSiblingComplaintId: candidate.top_sibling_id,
            candidateCount: candidates.length,
            threshold,
            refusedByDistance,
        };
    }

    // A new incident. The scores reported are the BEST candidate's — what
    // similarity alone would have decided — so the log stays comparable.
    return {
        incidentId: null,
        topSimilarity: best ? best.top_similarity : null,
        topSiblingComplaintId: best ? best.top_sibling_id : null,
        candidateCount: candidates.length,
        threshold,
        refusedByDistance,
    };
}
