package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One ward row of the Explorer (GET /dashboard/wards, N1). Every active ward is
 * returned, zeros included, so a map can colour every polygon.
 *
 * Officer-only. Never reuse this for the public strip — that is WardSummaryDto,
 * which is counts-only by design.
 */
@Getter
@AllArgsConstructor
public class WardExplorerSummaryDto {

    private final String wardId;
    private final String wardCode;
    private final String wardName;
    private final long complaintCount;
    private final long activeIncidentCount;
    private final long highPriorityIncidentCount;
    /** Canonical code with the most complaints; null when the ward has none. */
    private final String dominantCategory;
    /** max(reportedAt) of the ward's complaints; null when none. */
    private final String lastActivityAt;
}
