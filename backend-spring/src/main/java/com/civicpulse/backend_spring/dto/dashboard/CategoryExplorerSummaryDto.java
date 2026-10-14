package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One category row of the Explorer (GET /dashboard/categories, N2). Rows exist
 * for every active category and for any category that has data.
 */
@Getter
@AllArgsConstructor
public class CategoryExplorerSummaryDto {

    private final String category;
    private final long complaintCount;
    private final long activeIncidentCount;
    private final long highPriorityIncidentCount;
    /** Wards with at least one complaint in this category. */
    private final long wardCount;
    /** RESOLVED incidents only — CLOSED means no fix and is never counted. */
    private final long resolvedIncidentCount;
    /**
     * Median of resolvedAt − firstReportedAt, in hours. Null when nothing has
     * been resolved: "no data" is not "zero hours".
     */
    private final Double medianHoursToResolve;
    /** The category's default owner (V19); null = triage by hand. */
    private final String defaultDepartmentId;
    private final String lastActivityAt;
}
