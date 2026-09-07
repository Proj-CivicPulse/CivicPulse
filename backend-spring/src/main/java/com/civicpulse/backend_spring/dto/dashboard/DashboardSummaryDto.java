package com.civicpulse.backend_spring.dto.dashboard;

import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardSummaryDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** City-wide figures for the officer console. Officer role required. */
@Getter
@AllArgsConstructor
public class DashboardSummaryDto {

    private final long totalComplaints;
    private final long openComplaints;
    private final long resolvedComplaints;
    private final long totalIncidents;
    private final long openIncidents;
    private final long complaintsLast24h;
    private final long complaintsLast7d;
    private final List<CategoryCountDto> topCategories;
    private final List<WardSummaryDto> wards;
    private final List<IncidentDto> topIncidents;
}
