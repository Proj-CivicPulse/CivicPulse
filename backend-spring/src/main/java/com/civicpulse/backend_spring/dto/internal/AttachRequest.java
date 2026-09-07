package com.civicpulse.backend_spring.dto.internal;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Node decides the match; Spring performs the write.
 *
 * incidentId null means "start a new incident" — which is precisely why this
 * is /internal/incidents/attach rather than the sub-collection form
 * /internal/incidents/{id}/complaints that endpoints.md originally listed:
 * you cannot put a null in a path segment.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttachRequest {

    @NotBlank
    private String complaintId;

    /** null => create a new incident from this complaint. */
    private String incidentId;

    /** Cosine similarity that drove the decision. Recorded for Phase 8 analysis. */
    private Double similarity;
}
