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

    /**
     * The officer's call on the life-safety flag. Either value is final: the
     * text detector no longer changes the flag afterwards (V24).
     */
    private Boolean lifeSafety;

    /** Optional, with lifeSafety: what the officer saw. At most 255 characters. */
    private String lifeSafetyNote;
}
