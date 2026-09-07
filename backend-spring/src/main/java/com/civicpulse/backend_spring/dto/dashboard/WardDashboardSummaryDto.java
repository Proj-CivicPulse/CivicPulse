package com.civicpulse.backend_spring.dto.dashboard;

import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

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
}
