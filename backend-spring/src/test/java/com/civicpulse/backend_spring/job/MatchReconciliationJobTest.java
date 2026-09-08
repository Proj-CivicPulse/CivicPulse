package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.matching.ComplaintMatchingService;
import com.civicpulse.backend_spring.service.matching.MatchingCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchReconciliationJobTest {

    @Mock private ComplaintRepository complaintRepository;
    @Mock private ComplaintMatchingService matchingService;
    @Mock private MatchingCircuitBreaker circuitBreaker;

    private MatchReconciliationJob job;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getMatching().getReconciliation().setBatchSize(25);
        job = new MatchReconciliationJob(complaintRepository, matchingService, circuitBreaker, props);
        when(circuitBreaker.allowRequest()).thenReturn(true);
    }

    @Test
    @DisplayName("does nothing when the backlog is empty")
    void emptyBacklog() {
        when(complaintRepository.countReconcileBacklog(any())).thenReturn(0L);

        job.sweep();

        verify(complaintRepository, never()).findReconcileCandidates(any(), any());
        verify(matchingService, never()).run(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("holds off entirely while the circuit is open")
    void circuitOpen() {
        when(complaintRepository.countReconcileBacklog(any())).thenReturn(5L);
        when(circuitBreaker.allowRequest()).thenReturn(false);

        job.sweep();

        verify(complaintRepository, never()).findReconcileCandidates(any(), any());
        verify(matchingService, never()).run(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("re-runs the whole batch with force=true when Node stays reachable")
    void processesWholeBatch() {
        when(complaintRepository.countReconcileBacklog(any())).thenReturn(3L);
        when(complaintRepository.findReconcileCandidates(any(), any()))
                .thenReturn(List.of(complaint(1L), complaint(2L), complaint(3L)));
        when(matchingService.run(anyLong(), eq(true))).thenReturn(true);

        job.sweep();

        verify(matchingService).run(1L, true);
        verify(matchingService).run(2L, true);
        verify(matchingService).run(3L, true);
    }

    @Test
    @DisplayName("stops the batch at the first unreachable-Node result")
    void stopsOnNodeDown() {
        when(complaintRepository.countReconcileBacklog(any())).thenReturn(3L);
        when(complaintRepository.findReconcileCandidates(any(), any()))
                .thenReturn(List.of(complaint(1L), complaint(2L), complaint(3L)));
        when(matchingService.run(1L, true)).thenReturn(true);
        when(matchingService.run(2L, true)).thenReturn(false);

        job.sweep();

        verify(matchingService).run(1L, true);
        verify(matchingService).run(2L, true);
        verify(matchingService, never()).run(3L, true);
        verify(matchingService, times(2)).run(anyLong(), anyBoolean());
    }

    private static Complaint complaint(Long id) {
        return Complaint.builder().id(id).description("x").category("pothole").build();
    }
}
