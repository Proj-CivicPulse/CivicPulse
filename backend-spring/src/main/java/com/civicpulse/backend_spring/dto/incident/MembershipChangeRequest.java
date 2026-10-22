package com.civicpulse.backend_spring.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of POST /incidents/{id}/merge ({@code intoIncidentId} required) and of
 * POST /incidents/{id}/complaints/{complaintId}/unlink (optional). The note is
 * the officer's reason, kept in the match log.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MembershipChangeRequest {

    private String intoIncidentId;
    private String note;
}
