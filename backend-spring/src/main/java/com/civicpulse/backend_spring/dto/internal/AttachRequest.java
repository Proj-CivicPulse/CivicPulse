package com.civicpulse.backend_spring.dto.internal;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * backend-node decides the match; Spring performs the write.
 *
 * <p>{@code incidentId} null means "start a new incident" — which is why this is
 * {@code /internal/incidents/attach} rather than the sub-collection form
 * {@code /internal/incidents/{id}/complaints}: a null cannot occupy a path segment.
 *
 * <p>Every field below {@code incidentId} is score context for the
 * {@code incident_match_log} row Spring writes in the same transaction; it feeds
 * the Phase 8 evaluation and is otherwise unused. All are optional — the naive
 * fallback has none of them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttachRequest {

    @NotBlank
    private String complaintId;

    /** null / blank => create a new incident from this complaint. */
    private String incidentId;

    /** Best cosine similarity the matcher saw — recorded even when it lost to the threshold. */
    private Double topSimilarity;

    /** The member complaint the winning score was measured against (single-linkage). */
    private String topSiblingComplaintId;

    /** How many candidate incidents were scored. */
    private Integer candidateCount;

    /** The similarity threshold in effect for this decision. */
    private Double threshold;

    /** Embedding model id, e.g. {@code gemini-embedding-001}. */
    private String model;

    /** Embedding dimensionality. */
    private Integer embeddingDim;
}
