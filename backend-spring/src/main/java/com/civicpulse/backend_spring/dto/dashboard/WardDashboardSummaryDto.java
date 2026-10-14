package com.civicpulse.backend_spring.dto.dashboard;

import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class WardDashboardSummaryDto {

    private final WardDto ward;
    private final long totalComplaints;
    private final long openComplaints;
    private final long openIncidents;
    private final long complaintsLast7d;
    private final List<CategoryCountDto> topCategories;
    private final List<IncidentDto> topIncidents;

    // Explorer additions (E6). These honour the request's category and
    // from/to filters; the fields above are unfiltered and kept as they were
    // for compatibility. Category distribution is GET
    // /dashboard/categories?wardId= — one answer to that question, not two.

    /** Active incidents in the high or critical band. */
    private final long highPriorityIncidentCount;
    /** max(reportedAt) of the ward's complaints; null when none. */
    private final String lastActivityAt;
    /** Active incidents per band, every band present: low, medium, high, critical. */
    private final Map<String, Long> priorityDistribution;
    /** Incidents per status, every status present: open, in_progress, resolved, closed. */
    private final Map<String, Long> statusDistribution;
}
