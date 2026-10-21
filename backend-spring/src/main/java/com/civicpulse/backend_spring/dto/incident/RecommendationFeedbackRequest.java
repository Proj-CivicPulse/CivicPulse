package com.civicpulse.backend_spring.dto.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An officer's decision on the current recommendation
 * (POST /incidents/{id}/recommendation/feedback). What was recommended is
 * recomputed on the server, never taken from here; who decided comes from the
 * session.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationFeedbackRequest {

    /** accepted | overridden */
    @NotBlank
    private String decision;

    /** Required when overridden, and must differ from the recommendation. */
    private String chosenAction;

    @Size(max = 1000)
    private String note;
}
