package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.enums.Matcher;

/**
 * A matcher's decision about one complaint, handed to
 * {@link IncidentAttachmentService#attach}. Whoever decided the match, Spring
 * performs the write — see docs/service-boundaries.md decision 2.
 *
 * <p>The score fields are for the {@code incident_match_log} row that Spring
 * writes in the same transaction; they feed the Phase 8 evaluation and are
 * otherwise unused.
 *
 * @param incidentId            the incident to join, or {@code null} to start a new one
 * @param matcher               which matcher produced this
 * @param topSimilarity         best cosine similarity seen (recorded even when it lost)
 * @param topSiblingComplaintId member complaint the winning score was measured against
 * @param candidateCount        how many candidate incidents were scored
 * @param threshold             the similarity threshold in effect
 * @param model                 embedding model id, e.g. {@code gemini-embedding-001}
 * @param embeddingDim          embedding dimensionality
 */
public record MatchDecision(
        Long incidentId,
        Matcher matcher,
        Double topSimilarity,
        Long topSiblingComplaintId,
        Integer candidateCount,
        Double threshold,
        String model,
        Integer embeddingDim) {

    /** The semantic pipeline's decision, carrying the full evaluation context. */
    public static MatchDecision semantic(
            Long incidentId,
            Double topSimilarity,
            Long topSiblingComplaintId,
            Integer candidateCount,
            Double threshold,
            String model,
            Integer embeddingDim) {
        return new MatchDecision(incidentId, Matcher.SEMANTIC, topSimilarity,
                topSiblingComplaintId, candidateCount, threshold, model, embeddingDim);
    }

    /** The naive fallback's decision — ward + category + window + radius, no scores. */
    public static MatchDecision naive(Long incidentId, Integer candidateCount) {
        return new MatchDecision(incidentId, Matcher.NAIVE, null, null,
                candidateCount, null, null, null);
    }
}
