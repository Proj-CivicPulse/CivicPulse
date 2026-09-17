package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.data.domain.Pageable.ofSize;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriorityRefreshJobTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentAttachmentService attachmentService;

    private PriorityRefreshJob job;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getPriority().getRefresh().setBatchSize(50);
        props.getPriority().getRefresh().setStaleAfterMinutes(360);
        job = new PriorityRefreshJob(incidentRepository, attachmentService, props);
    }

    private static Incident incident(long id) {
        return Incident.builder().id(id).status(IncidentStatus.OPEN).build();
    }

    @Test
    @DisplayName("does nothing when no score has drifted")
    void nothingStale() {
        when(incidentRepository.findStalePriority(any(), any(), any())).thenReturn(List.of());

        job.sweep();

        verify(attachmentService, never()).recomputeById(anyLong());
    }

    @Test
    @DisplayName("recomputes every stale incident in the batch")
    void recomputesBatch() {
        when(incidentRepository.findStalePriority(any(), any(), any()))
                .thenReturn(List.of(incident(1L), incident(2L), incident(3L)));

        job.sweep();

        verify(attachmentService).recomputeById(1L);
        verify(attachmentService).recomputeById(2L);
        verify(attachmentService).recomputeById(3L);
    }

    @Test
    @DisplayName("asks only for actionable incidents older than the stale window")
    void queriesActiveAndStale() {
        when(incidentRepository.findStalePriority(any(), any(), any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        job.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IncidentStatus>> statuses =
                ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(incidentRepository)
                .findStalePriority(statuses.capture(), staleBefore.capture(), eq(ofSize(50)));

        // A resolved incident's score records how urgent it was while it was
        // live. Ageing it further would rewrite that for no reader.
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);
        assertThat(staleBefore.getValue()).isBefore(before.minusMinutes(359));
    }

    @Test
    @DisplayName("one failing incident does not abandon the rest of the batch")
    void oneFailureDoesNotStopTheSweep() {
        // The realistic case is an incident deleted between the query and the
        // recompute — a normal race, not a reason to stall the sweep.
        when(incidentRepository.findStalePriority(any(), any(), any()))
                .thenReturn(List.of(incident(1L), incident(2L), incident(3L)));
        when(attachmentService.recomputeById(2L)).thenThrow(new IllegalStateException("gone"));

        job.sweep();

        verify(attachmentService).recomputeById(1L);
        verify(attachmentService).recomputeById(3L);
    }
}
