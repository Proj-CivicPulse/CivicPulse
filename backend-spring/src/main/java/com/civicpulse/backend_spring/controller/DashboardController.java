package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.dashboard.CategoryExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.DashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.TrendDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardExplorerSummaryDto;
import com.civicpulse.backend_spring.service.dashboard.DashboardService;
import com.civicpulse.backend_spring.service.explorer.ExplorerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OFFICER-gated by the /dashboard/** matcher in SecurityConfig.
 *
 * <p>The Explorer's aggregates live here rather than under a family of
 * /ward-stats, /ward-analytics style paths. Shapes and filter
 * semantics: docs/api-contract.md, E5, E6 and N1–N3.
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ExplorerService explorerService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryDto> summary() {
        return ResponseEntity.ok(dashboardService.summary());
    }

    /** E6: the ward detail summary. Filters apply to the Explorer fields only. */
    @GetMapping("/wards/{wardId}/summary")
    public ResponseEntity<WardDashboardSummaryDto> wardSummary(
            @PathVariable Long wardId,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.wardSummary(wardId, category, from, to));
    }

    /** N1: one row per active ward, zeros included. */
    @GetMapping("/wards")
    public ResponseEntity<List<WardExplorerSummaryDto>> wards(
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(explorerService.wardSummaries(category, from, to));
    }

    /** N2: one row per category that is active or has data. */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryExplorerSummaryDto>> categories(
            @RequestParam(required = false) List<Long> wardId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(explorerService.categorySummaries(wardId, from, to));
    }

    /** N3: complaint counts per day or week, by report time. from and to are required. */
    @GetMapping("/trends")
    public ResponseEntity<TrendDto> trends(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) List<Long> wardId,
            @RequestParam(required = false) List<String> category) {
        return ResponseEntity.ok(explorerService.trends(from, to, bucket, groupBy, wardId, category));
    }
}
