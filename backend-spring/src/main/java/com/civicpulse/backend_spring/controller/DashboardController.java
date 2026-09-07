package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.dashboard.DashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.service.dashboard.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OFFICER-gated by the /dashboard/** matcher in SecurityConfig. */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryDto> summary() {
        return ResponseEntity.ok(dashboardService.summary());
    }

    @GetMapping("/wards/{wardId}/summary")
    public ResponseEntity<WardDashboardSummaryDto> wardSummary(@PathVariable Long wardId) {
        return ResponseEntity.ok(dashboardService.wardSummary(wardId));
    }
}
