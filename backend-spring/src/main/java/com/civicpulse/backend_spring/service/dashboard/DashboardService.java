package com.civicpulse.backend_spring.service.dashboard;

import com.civicpulse.backend_spring.dto.dashboard.CategoryCountDto;
import com.civicpulse.backend_spring.dto.dashboard.DashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.repository.projection.CategoryCountRow;
import com.civicpulse.backend_spring.service.ward.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Officer-only aggregates. Requires the OFFICER role at the controller. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final List<IncidentStatus> ACTIVE =
            List.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);

    private static final int TOP_CATEGORIES = 5;
    private static final int TOP_INCIDENTS = 5;

    private final ComplaintRepository complaintRepository;
    private final IncidentRepository incidentRepository;
    private final WardService wardService;

    @Transactional(readOnly = true)
    public DashboardSummaryDto summary() {
        LocalDateTime now = LocalDateTime.now();

        return new DashboardSummaryDto(
                complaintRepository.count(),
                complaintRepository.countByStatus(ComplaintStatus.OPEN),
                complaintRepository.countByStatus(ComplaintStatus.RESOLVED),
                incidentRepository.count(),
                incidentRepository.countByStatusIn(ACTIVE),
                complaintRepository.countByCreatedAtAfter(now.minusDays(1)),
                complaintRepository.countByCreatedAtAfter(now.minusDays(7)),
                topCategories(null),
                // Same narrow shape the public strip uses, deliberately — one
                // definition of "ward plus open count", not two.
                wardService.summary(),
                topIncidents(null));
    }

    @Transactional(readOnly = true)
    public WardDashboardSummaryDto wardSummary(Long wardId) {
        Ward ward = wardService.requireWard(wardId);
        LocalDateTime now = LocalDateTime.now();

        return new WardDashboardSummaryDto(
                WardDto.from(ward),
                complaintRepository.countByWardId(wardId),
                complaintRepository.countByWardIdAndStatus(wardId, ComplaintStatus.OPEN),
                incidentRepository.countByWardIdAndStatusIn(wardId, ACTIVE),
                complaintRepository.countByWardIdAndCreatedAtAfter(wardId, now.minusDays(7)),
                topCategories(wardId),
                topIncidents(wardId));
    }

    private List<CategoryCountDto> topCategories(Long wardId) {
        List<CategoryCountRow> rows = complaintRepository.topCategories(
                wardId, PageRequest.of(0, TOP_CATEGORIES));
        return rows.stream()
                .map(row -> new CategoryCountDto(row.getCategory(), row.getTotal()))
                .toList();
    }

    private List<IncidentDto> topIncidents(Long wardId) {
        var sort = Sort.by(Sort.Direction.DESC, "priorityScore");
        var page = PageRequest.of(0, TOP_INCIDENTS, sort);

        var incidents = wardId == null
                ? incidentRepository.findAll(page).getContent()
                : incidentRepository.findAll(
                        (root, query, cb) -> cb.equal(root.get("ward").get("id"), wardId),
                        page).getContent();

        return incidents.stream().map(IncidentDto::from).toList();
    }
}
