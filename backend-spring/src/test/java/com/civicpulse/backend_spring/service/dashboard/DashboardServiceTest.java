package com.civicpulse.backend_spring.service.dashboard;

import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.ward.WardService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two dashboard semantics the Explorer depends on
 * (docs/data-quality-rules.md §3 and §5). Both were wrong in a way today's data
 * could not show: every complaint has reported_at = created_at, and every
 * incident is OPEN.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private WardService wardService;

    private DashboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new DashboardService(complaintRepository, incidentRepository, wardService);
        when(wardService.summary()).thenReturn(List.of());
        when(complaintRepository.topCategories(any(), any())).thenReturn(List.of());
        when(incidentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    @Test
    @DisplayName("'last 24h / 7d' count when problems were REPORTED, not when rows arrived")
    void windowsUseReportedAt() {
        LocalDateTime before = LocalDateTime.now();

        service.summary();

        // An import of last year's complaints arrives today; counting arrival
        // would show it as this week's activity.
        verify(complaintRepository).countByReportedAtAfter(
                argThat(since -> !since.isBefore(before.minusDays(1).minusSeconds(5))
                        && since.isBefore(before.minusHours(23))));
        verify(complaintRepository).countByReportedAtAfter(
                argThat(since -> since.isBefore(before.minusDays(6))));
    }

    @Test
    @DisplayName("the ward summary's 7-day window is on reported_at too")
    void wardWindowUsesReportedAt() {
        when(wardService.requireWard(7L)).thenReturn(
                Ward.builder().id(7L).code("7").name("Ward 7").active(true).build());

        service.wardSummary(7L);

        verify(complaintRepository).countByWardIdAndReportedAtAfter(eq(7L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("top incidents are ACTIVE incidents only, ranked by score with a stable tie-break")
    @SuppressWarnings("unchecked")
    void topIncidentsAreActiveOnly() {
        service.summary();

        ArgumentCaptor<Specification<Incident>> spec = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findAll(spec.capture(), page.capture());

        // City-wide (no ward), the composed spec reduces to the status filter.
        Root<Incident> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> statusPath = mock(Path.class);
        Predicate activeOnly = mock(Predicate.class);
        doReturn(statusPath).when(root).get("status");
        when(statusPath.in(List.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS)))
                .thenReturn(activeOnly);

        assertThat(spec.getValue().toPredicate(root, query, cb)).isSameAs(activeOnly);

        assertThat(page.getValue().getPageSize()).isEqualTo(5);
        assertThat(page.getValue().getSort()).containsExactly(
                Sort.Order.desc("priorityScore"), Sort.Order.asc("id"));
    }
}
