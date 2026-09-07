package com.civicpulse.backend_spring.dto.complaint;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PATCH semantics: an absent field means "leave unchanged".
 *
 * A plain DTO cannot distinguish absent from explicit null, so null is treated
 * as absent. "Set department back to null" is not a use case, and wrapping two
 * fields in Optional/JsonNullable to model it would cost more than it buys.
 * Both absent is rejected as a validation error rather than silently doing
 * nothing.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateComplaintRequest {

    private String status;
    private String departmentId;
}
