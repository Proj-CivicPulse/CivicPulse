package com.civicpulse.backend_spring.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Same PATCH semantics as UpdateComplaintRequest: absent means unchanged. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateIncidentRequest {

    private String status;
    private String departmentId;
    private String title;
    private String summary;
}
