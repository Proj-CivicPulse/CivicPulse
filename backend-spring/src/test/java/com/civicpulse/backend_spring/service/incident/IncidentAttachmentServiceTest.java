package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.internal.AttachResponse;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentMatchLogRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.geocoding.GeocodingService;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import com.civicpulse.backend_spring.service.priority.PriorityResult;
import com.civicpulse.backend_spring.service.priority.PriorityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the write path both matchers share: which {@code matching_status} each
 * produces, that every decision lands one {@code incident_match_log} row, the
 * reconcile reattach (previous incident recorded, emptied incident removed), and
 * the guard that stops a late naive fallback overwriting a semantic result.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentAttachmentServiceTest {

    private static final Ward WARD = Ward.builder().id(7L).code("17").name("Ward 17").build();

    @Mock private ComplaintRepository complaintRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentMatchLogRepository matchLogRepository;
    @Mock private PriorityService priorityService;
    @Mock private GeocodingService geocodingService;

    private IncidentAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new IncidentAttachmentService(
                complaintRepository, incidentRepository, matchLogRepository,
                priorityService, geocodingService);

        when(geocodingService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(Optional.empty());
        when(priorityService.compute(any(), any(), any()))
                .thenReturn(new PriorityResult(1.0, PriorityBand.LOW, List.of("Single report — awaiting corroboration")));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> {
            Incident i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(100L);
            }
            return i;
        });
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a semantic decision that starts a new incident -> MATCHED + a CREATED log row")
    void semanticCreate() {
        Complaint complaint = complaint(1L, null);
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(100L))
                .thenReturn(List.of(complaint));

        AttachResponse response = service.attach(1L, MatchDecision.semantic(
                null, 0.42, null, 3, 0.75, "gemini-embedding-001", 1536));

        assertThat(complaint.getMatchingStatus()).isEqualTo(MatchingStatus.MATCHED);
        assertThat(complaint.getMatchedAt()).isNotNull();
        assertThat(response.isCreated()).isTrue();
        assertThat(response.getMatchingStatus()).isEqualTo("matched");

        IncidentMatchLog logged = captureLog();
        assertThat(logged.getDecision()).isEqualTo(MatchOutcome.CREATED);
        assertThat(logged.getMatcher()).isEqualTo(Matcher.SEMANTIC);
        assertThat(logged.getChosenIncidentId()).isEqualTo(100L);
        assertThat(logged.getPreviousIncidentId()).isNull();
        assertThat(logged.getTopSimilarity()).isEqualTo(0.42);
        assertThat(logged.getThreshold()).isEqualTo(0.75);
        assertThat(logged.getModel()).isEqualTo("gemini-embedding-001");
        assertThat(logged.getEmbeddingDim()).isEqualTo(1536);
    }

    @Test
    @DisplayName("a semantic decision that joins an existing incident -> MATCHED + a MATCHED log row")
    void semanticJoin() {
        Complaint complaint = complaint(1L, null);
        Incident existing = incident(55L);
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(existing));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(complaint));

        service.attach(1L, MatchDecision.semantic(55L, 0.88, 4821L, 2, 0.75, "gemini-embedding-001", 1536));

        assertThat(complaint.getIncident()).isEqualTo(existing);
        assertThat(complaint.getMatchingStatus()).isEqualTo(MatchingStatus.MATCHED);

        IncidentMatchLog logged = captureLog();
        assertThat(logged.getDecision()).isEqualTo(MatchOutcome.MATCHED);
        assertThat(logged.getChosenIncidentId()).isEqualTo(55L);
        assertThat(logged.getTopSiblingComplaintId()).isEqualTo(4821L);
    }

    @Test
    @DisplayName("a naive decision -> DEGRADED + a NAIVE log row with no scores")
    void naiveDegraded() {
        Complaint complaint = complaint(1L, null);
        Incident existing = incident(55L);
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(existing));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(complaint));

        AttachResponse response = service.attach(1L, MatchDecision.naive(55L, 1));

        assertThat(complaint.getMatchingStatus()).isEqualTo(MatchingStatus.DEGRADED);
        assertThat(response.getMatchingStatus()).isEqualTo("degraded");

        IncidentMatchLog logged = captureLog();
        assertThat(logged.getMatcher()).isEqualTo(Matcher.NAIVE);
        assertThat(logged.getDecision()).isEqualTo(MatchOutcome.MATCHED);
        assertThat(logged.getTopSimilarity()).isNull();
        assertThat(logged.getThreshold()).isNull();
    }

    @Test
    @DisplayName("naive fallback is a no-op once the complaint is already MATCHED")
    void naiveDoesNotClobberSemantic() {
        Complaint complaint = complaint(1L, incident(55L));
        complaint.setMatchingStatus(MatchingStatus.MATCHED);
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));

        AttachResponse response = service.attach(1L, MatchDecision.naive(99L, 1));

        assertThat(response.getIncidentId()).isEqualTo("55");
        assertThat(response.getMatchingStatus()).isEqualTo("matched");
        verify(matchLogRepository, never()).save(any());
        verify(complaintRepository, never()).save(any());
    }

    @Test
    @DisplayName("naive fallback is a no-op while a Node run holds the PROCESSING claim")
    void naiveDefersToInFlightSemantic() {
        Complaint complaint = complaint(1L, null);
        complaint.setMatchingStatus(MatchingStatus.PROCESSING);
        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));

        service.attach(1L, MatchDecision.naive(null, 0));

        verify(matchLogRepository, never()).save(any());
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("reconcile move: previous incident recorded, emptied incident deleted, RECONCILED logged")
    void reconcileMoveDeletesEmptiedIncident() {
        Incident stale = incident(55L);
        Incident target = incident(70L);
        Complaint complaint = complaint(1L, stale);
        complaint.setMatchingStatus(MatchingStatus.DEGRADED);

        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));
        when(incidentRepository.findById(70L)).thenReturn(Optional.of(target));
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(stale));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(70L)).thenReturn(List.of(complaint));
        // the stale incident has no members left after the move
        when(complaintRepository.countByIncidentId(55L)).thenReturn(0L);

        service.attach(1L, MatchDecision.semantic(70L, 0.91, 4821L, 1, 0.75, "gemini-embedding-001", 1536));

        assertThat(complaint.getIncident()).isEqualTo(target);
        assertThat(complaint.getMatchingStatus()).isEqualTo(MatchingStatus.MATCHED);
        verify(incidentRepository).delete(stale);

        IncidentMatchLog logged = captureLog();
        assertThat(logged.getDecision()).isEqualTo(MatchOutcome.RECONCILED);
        assertThat(logged.getPreviousIncidentId()).isEqualTo(55L);
        assertThat(logged.getChosenIncidentId()).isEqualTo(70L);
        // moved into an EXISTING incident — the join half of the join/split signal
        assertThat(logged.isCreated()).isFalse();
    }

    @Test
    @DisplayName("a reconcile move into a NEW incident is flagged created, which RECONCILED alone cannot express")
    void reconcileMoveIntoNewIncidentIsFlaggedCreated() {
        Incident stale = incident(55L);
        Complaint complaint = complaint(1L, stale);
        complaint.setMatchingStatus(MatchingStatus.DEGRADED);

        when(complaintRepository.findById(1L)).thenReturn(Optional.of(complaint));
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(stale));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(100L)).thenReturn(List.of(complaint));
        when(complaintRepository.countByIncidentId(55L)).thenReturn(0L);

        // incidentId null => the matcher found nothing similar and split it out
        service.attach(1L, MatchDecision.semantic(null, 0.31, null, 2, 0.75, "gemini-embedding-001", 1536));

        IncidentMatchLog logged = captureLog();
        assertThat(logged.getDecision()).isEqualTo(MatchOutcome.RECONCILED);
        assertThat(logged.isCreated()).isTrue();
        assertThat(logged.getPreviousIncidentId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("reconcile move: an incident that still has members is recomputed, not deleted")
    void reconcileMoveKeepsNonEmptyIncident() {
        Incident stale = incident(55L);
        Incident target = incident(70L);
        Complaint moved = complaint(1L, stale);
        Complaint stayed = complaint(2L, stale);
        moved.setMatchingStatus(MatchingStatus.DEGRADED);

        when(complaintRepository.findById(1L)).thenReturn(Optional.of(moved));
        when(incidentRepository.findById(70L)).thenReturn(Optional.of(target));
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(stale));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(70L)).thenReturn(List.of(moved));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(stayed));
        when(complaintRepository.countByIncidentId(55L)).thenReturn(1L);

        service.attach(1L, MatchDecision.semantic(70L, 0.91, 4821L, 1, 0.75, "gemini-embedding-001", 1536));

        verify(incidentRepository, never()).delete(any(Incident.class));
        // recompute(stale) ran: it was saved after the member list was reread
        verify(incidentRepository).save(stale);
    }

    @Test
    @DisplayName("recomputeById re-derives the score without writing a match-log row or touching matching_status")
    void recomputeIsNotAMatchingDecision() {
        Incident incident = incident(55L);
        Complaint member = complaint(1L, incident);
        member.setMatchingStatus(MatchingStatus.DEGRADED);
        when(incidentRepository.findById(55L)).thenReturn(Optional.of(incident));
        when(complaintRepository.findByIncidentIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(member));

        service.recomputeById(55L);

        verify(incidentRepository).save(incident);
        // The Phase 8 dataset must only ever record real matching decisions.
        verify(matchLogRepository, never()).save(any());
        verify(complaintRepository, never()).save(any());
        assertThat(member.getMatchingStatus()).isEqualTo(MatchingStatus.DEGRADED);
    }

    private IncidentMatchLog captureLog() {
        ArgumentCaptor<IncidentMatchLog> captor = ArgumentCaptor.forClass(IncidentMatchLog.class);
        verify(matchLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Complaint complaint(Long id, Incident incident) {
        return Complaint.builder()
                .id(id)
                .ward(WARD)
                .description("pothole on the main road")
                .category("pothole")
                .latitude(12.9716)
                .longitude(77.5946)
                .incident(incident)
                .matchingStatus(MatchingStatus.PENDING)
                .build();
    }

    private static Incident incident(Long id) {
        return Incident.builder()
                .id(id)
                .ward(WARD)
                .category("pothole")
                .latitude(12.9716)
                .longitude(77.5946)
                .priorityScore(0.0)
                .build();
    }
}
