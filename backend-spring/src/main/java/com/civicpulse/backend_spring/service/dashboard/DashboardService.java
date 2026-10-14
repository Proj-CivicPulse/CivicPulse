package com.civicpulse.backend_spring.service.dashboard;

import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.dashboard.CategoryCountDto;
import com.civicpulse.backend_spring.dto.dashboard.DashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.ExplorerRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.repository.projection.CategoryCountRow;
import com.civicpulse.backend_spring.repository.spec.IncidentSpecifications;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.explorer.ExplorerFilter;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import com.civicpulse.backend_spring.service.ward.WardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ExplorerRepository explorerRepository;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public DashboardSummaryDto summary() {
        LocalDateTime now = LocalDateTime.now();

        return new DashboardSummaryDto(
                complaintRepository.count(),
                complaintRepository.countByStatus(ComplaintStatus.OPEN),
                complaintRepository.countByStatus(ComplaintStatus.RESOLVED),
                incidentRepository.count(),
                incidentRepository.countByStatusIn(ACTIVE),
                incidentRepository.countByStatusInAndPriorityScoreGreaterThanEqual(
                        ACTIVE, PriorityBand.HIGH.lowerBound()),
                complaintRepository.countByReportedAtAfter(now.minusDays(1)),
                complaintRepository.countByReportedAtAfter(now.minusDays(7)),
                topCategories(null),
                // Same narrow shape the public strip uses, deliberately — one
                // definition of "ward plus open count", not two.
                wardService.summary(),
                topIncidents(null));
    }

    @Transactional(readOnly = true)
    public WardDashboardSummaryDto wardSummary(Long wardId) {
        return wardSummary(wardId, null, null, null);
    }

    /**
     * The ward detail summary (E6). The category and from/to filters apply to
     * the Explorer fields — high-priority count, last activity and the two
     * distributions; the original fields stay unfiltered for compatibility.
     *
     * <p>404 for a retired ward as well as an unknown one: the LEGACY-*
     * placeholders exist only so old rows stay readable, and no Explorer view
     * is built on them.
     */
    @Transactional(readOnly = true)
    public WardDashboardSummaryDto wardSummary(Long wardId, List<String> categories, String from, String to) {
        Ward ward = wardService.requireWard(wardId);
        if (!ward.isActive()) {
            throw new ResourceNotFoundException("Ward not found");
        }
        ExplorerFilter filter = new ExplorerFilter(
                List.of(),
                QueryParams.each(categories, categoryService::canonicaliseFilter),
                QueryParams.instant(from, "from"),
                QueryParams.instant(to, "to"));
        LocalDateTime now = LocalDateTime.now();

        // Bands assigned with PriorityBand.of, so the distribution uses exactly
        // the classification each Incident carries on the wire.
        Map<String, Long> byBand = new LinkedHashMap<>();
        for (PriorityBand band : PriorityBand.values()) {
            byBand.put(band.wireValue(), 0L);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (IncidentStatus status : IncidentStatus.values()) {
            byStatus.put(Wire.enumValue(status), 0L);
        }
        long highPriority = 0;
        for (ExplorerRepository.IncidentState state : explorerRepository.incidentStates(wardId, filter)) {
            IncidentStatus status = IncidentStatus.valueOf(state.status());
            byStatus.merge(Wire.enumValue(status), 1L, Long::sum);
            if (ACTIVE.contains(status)) {
                PriorityBand band = PriorityBand.of(state.priorityScore());
                byBand.merge(band.wireValue(), 1L, Long::sum);
                if (band.compareTo(PriorityBand.HIGH) >= 0) {
                    highPriority++;
                }
            }
        }

        return new WardDashboardSummaryDto(
                WardDto.from(ward),
                complaintRepository.countByWardId(wardId),
                complaintRepository.countByWardIdAndStatus(wardId, ComplaintStatus.OPEN),
                incidentRepository.countByWardIdAndStatusIn(wardId, ACTIVE),
                complaintRepository.countByWardIdAndReportedAtAfter(wardId, now.minusDays(7)),
                topCategories(wardId),
                topIncidents(wardId),
                highPriority,
                Wire.timestamp(explorerRepository.lastActivity(wardId, filter)),
                byBand,
                byStatus);
    }

    private List<CategoryCountDto> topCategories(Long wardId) {
        List<CategoryCountRow> rows = complaintRepository.topCategories(
                wardId, PageRequest.of(0, TOP_CATEGORIES));
        return rows.stream()
                .map(row -> new CategoryCountDto(row.getCategory(), row.getTotal()))
                .toList();
    }

    /**
     * The highest-priority ACTIVE incidents. A resolved or closed incident keeps
     * the score it had while live, so ranking every status would let finished
     * work occupy the slots meant for what still needs doing. Ties break on id
     * so the list is stable between calls.
     */
    private List<IncidentDto> topIncidents(Long wardId) {
        var sort = Sort.by(Sort.Direction.DESC, "priorityScore")
                .and(Sort.by(Sort.Direction.ASC, "id"));
        var page = PageRequest.of(0, TOP_INCIDENTS, sort);

        Specification<Incident> spec = Specification.allOf(
                IncidentSpecifications.hasWard(wardId),
                IncidentSpecifications.hasStatusIn(ACTIVE));

        return incidentRepository.findAll(spec, page).getContent().stream()
                .map(IncidentDto::from)
                .toList();
    }
}
