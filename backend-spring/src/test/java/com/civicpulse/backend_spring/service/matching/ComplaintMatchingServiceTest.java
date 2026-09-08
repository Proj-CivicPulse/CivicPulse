package com.civicpulse.backend_spring.service.matching;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import com.civicpulse.backend_spring.service.incident.MatchDecision;
import com.civicpulse.backend_spring.service.incident.NaiveIncidentGrouper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trigger's failure handling: which outcomes count against the breaker,
 * when the naive fallback runs, and the slow-Node case where the PROCESSING
 * claim means "leave it alone, the real result is coming".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplaintMatchingServiceTest {

    private static final Ward WARD = Ward.builder().id(7L).code("17").build();

    @Mock private NodeMatchingClient nodeClient;
    @Mock private MatchingCircuitBreaker circuitBreaker;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private NaiveIncidentGrouper grouper;
    @Mock private IncidentAttachmentService attachmentService;

    private ComplaintMatchingService service;

    @BeforeEach
    void setUp() {
        service = new ComplaintMatchingService(
                nodeClient, circuitBreaker, complaintRepository, grouper, attachmentService);
        when(circuitBreaker.allowRequest()).thenReturn(true);
        when(grouper.isEnabled()).thenReturn(true);
        when(grouper.findMatch(any())).thenReturn(new NaiveIncidentGrouper.NaiveMatch(null, 0));
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint(MatchingStatus.PENDING)));
    }

    @Test
    @DisplayName("Node answers: success recorded, no fallback")
    void nodeSucceeds() {
        service.run(1L, false);

        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
        verify(attachmentService, never()).attach(any(), any());
    }

    @Test
    @DisplayName("Node unreachable: failure recorded, naive fallback runs")
    void nodeConnectionFails() {
        doThrow(new NodeConnectionException("refused", null))
                .when(nodeClient).process(eq(1L), anyBoolean());

        boolean reachable = service.run(1L, false);

        assertThat(reachable).isFalse();
        verify(circuitBreaker).recordFailure();
        verify(attachmentService).attach(eq(1L), any(MatchDecision.class));
    }

    @Test
    @DisplayName("circuit open: Node is not called, fallback runs immediately")
    void circuitOpen() {
        when(circuitBreaker.allowRequest()).thenReturn(false);

        boolean reachable = service.run(1L, false);

        assertThat(reachable).isFalse();
        verify(nodeClient, never()).process(any(), anyBoolean());
        verify(attachmentService).attach(eq(1L), any(MatchDecision.class));
    }

    @Test
    @DisplayName("Node slow but holding the PROCESSING claim: treated as success, no fallback")
    void nodeSlowButWorking() {
        doThrow(new NodeSlowException("timeout", null))
                .when(nodeClient).process(eq(1L), anyBoolean());
        when(complaintRepository.findById(1L))
                .thenReturn(Optional.of(complaint(MatchingStatus.PROCESSING)));

        boolean reachable = service.run(1L, false);

        assertThat(reachable).isTrue();
        verify(circuitBreaker).recordSuccess();
        verify(circuitBreaker, never()).recordFailure();
        verify(attachmentService, never()).attach(any(), any());
    }

    @Test
    @DisplayName("Node slow and the request never landed (still PENDING): failure + fallback")
    void nodeSlowRequestLost() {
        doThrow(new NodeSlowException("timeout", null))
                .when(nodeClient).process(eq(1L), anyBoolean());
        // findById already returns PENDING from setUp

        boolean reachable = service.run(1L, false);

        assertThat(reachable).isFalse();
        verify(circuitBreaker).recordFailure();
        verify(attachmentService).attach(eq(1L), any(MatchDecision.class));
    }

    @Test
    @DisplayName("fallback is skipped when the complaint is no longer PENDING")
    void fallbackSkippedWhenNotPending() {
        doThrow(new NodeConnectionException("refused", null))
                .when(nodeClient).process(eq(1L), anyBoolean());
        when(complaintRepository.findById(1L))
                .thenReturn(Optional.of(complaint(MatchingStatus.PROCESSING)));

        service.run(1L, false);

        verify(attachmentService, never()).attach(any(), any());
    }

    @Test
    @DisplayName("the naive decision carries the grouper's candidate count")
    void fallbackPassesCandidateCount() {
        doThrow(new NodeConnectionException("refused", null))
                .when(nodeClient).process(eq(1L), anyBoolean());
        Incident existing = Incident.builder().id(55L).ward(WARD).category("pothole").build();
        when(grouper.findMatch(any())).thenReturn(new NaiveIncidentGrouper.NaiveMatch(existing, 4));

        service.run(1L, false);

        ArgumentCaptor<MatchDecision> captor = ArgumentCaptor.forClass(MatchDecision.class);
        verify(attachmentService).attach(eq(1L), captor.capture());
        assertThat(captor.getValue().incidentId()).isEqualTo(55L);
        assertThat(captor.getValue().candidateCount()).isEqualTo(4);
    }

    private static Complaint complaint(MatchingStatus status) {
        return Complaint.builder()
                .id(1L)
                .ward(WARD)
                .description("pothole")
                .category("pothole")
                .matchingStatus(status)
                .build();
    }
}
