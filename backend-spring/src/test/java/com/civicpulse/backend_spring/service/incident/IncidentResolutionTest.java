package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.category.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pins {@code incidents.resolved_at} to the status it describes.
 *
 * <p>The column exists so the Explorer can measure resolution time, and the only
 * way it can lie is by disagreeing with {@code status} — a stamp left behind on a
 * reopened incident, or a stamp moved by an edit that was not a resolution. Both
 * are silent: every query still returns a number, just the wrong one. Hence a
 * test per transition rather than one happy path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentResolutionTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private CategoryService categoryService;

    private IncidentService service;

    @BeforeEach
    void setUp() {
        service = new IncidentService(
                incidentRepository, complaintRepository, departmentRepository, categoryService);

        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Incident existing(IncidentStatus status, LocalDateTime resolvedAt) {
        Incident incident = Incident.builder()
                .id(1L)
                .title("Pothole on 4th Main")
                .category("pothole")
                .status(status)
                .resolvedAt(resolvedAt)
                .build();
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));
        return incident;
    }

    private IncidentDto update(String status) {
        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setStatus(status);
        return service.update(1L, request);
    }

    @Test
    @DisplayName("resolving an open incident stamps resolved_at")
    void stampsOnResolve() {
        Incident incident = existing(IncidentStatus.OPEN, null);
        LocalDateTime before = LocalDateTime.now();

        IncidentDto dto = update("resolved");

        assertThat(incident.getResolvedAt()).isNotNull();
        assertThat(incident.getResolvedAt()).isAfterOrEqualTo(before);
        assertThat(dto.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("re-resolving does not move an existing resolved_at")
    void idempotentOnRepeatedResolve() {
        // An officer correcting the summary of a resolved incident must not
        // change when it was resolved, and "resolved -> resolved" is what that
        // request looks like from here.
        LocalDateTime original = LocalDateTime.now().minusDays(3);
        Incident incident = existing(IncidentStatus.RESOLVED, original);

        update("resolved");

        assertThat(incident.getResolvedAt()).isEqualTo(original);
    }

    @Test
    @DisplayName("reopening a resolved incident clears resolved_at")
    void clearsOnReopen() {
        // Left in place, this row would be counted by every "resolved in this
        // period" query while the problem is demonstrably still open.
        Incident incident = existing(IncidentStatus.RESOLVED, LocalDateTime.now().minusDays(1));

        IncidentDto dto = update("open");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(dto.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("moving a resolved incident to in_progress also clears it")
    void clearsOnAnyNonResolvedStatus() {
        Incident incident = existing(IncidentStatus.RESOLVED, LocalDateTime.now().minusDays(1));

        update("in_progress");

        assertThat(incident.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("closing a resolved incident clears resolved_at — closed means no fix")
    void clearsOnClose() {
        // CLOSED is "terminated without a fix", so RESOLVED -> CLOSED is a
        // correction that the fix never happened. Keeping the stamp would count
        // an unfixed problem in every resolution metric.
        Incident incident = existing(IncidentStatus.RESOLVED, LocalDateTime.now().minusDays(1));

        IncidentDto dto = update("closed");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(dto.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("closing an open incident never stamps resolved_at")
    void closeIsNotAResolution() {
        Incident incident = existing(IncidentStatus.OPEN, null);

        update("closed");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(incident.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("an edit that does not touch status leaves resolved_at alone")
    void untouchedByOtherEdits() {
        LocalDateTime original = LocalDateTime.now().minusDays(2);
        Incident incident = existing(IncidentStatus.RESOLVED, original);

        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setSummary("Repaired, pending inspection");
        service.update(1L, request);

        assertThat(incident.getResolvedAt()).isEqualTo(original);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    @DisplayName("an open incident stays unstamped through a non-resolving edit")
    void openStaysUnstamped() {
        Incident incident = existing(IncidentStatus.OPEN, null);

        update("in_progress");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(incident.getResolvedAt()).isNull();
    }
}
