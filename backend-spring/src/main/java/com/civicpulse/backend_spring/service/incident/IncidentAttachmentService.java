package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.internal.AttachResponse;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentMatchLogRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.priority.PriorityResult;
import com.civicpulse.backend_spring.service.geocoding.GeocodingService;
import com.civicpulse.backend_spring.service.priority.PriorityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The single transactional write that puts a complaint into an incident.
 *
 * Spring performs every incident write (docs/service-boundaries.md decision 2),
 * whoever decided the match — the semantic matcher in backend-node, or the
 * {@link NaiveIncidentGrouper} fallback. {@link MatchDecision} carries who
 * decided and the score context; this method turns that into rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentAttachmentService {

    private final ComplaintRepository complaintRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentMatchLogRepository matchLogRepository;
    private final PriorityService priorityService;
    private final GeocodingService geocodingService;

    /**
     * @param decision the matcher's call — {@code incidentId} null means "start a
     *                 new incident from this complaint"
     */
    @Transactional
    public AttachResponse attach(Long complaintId, MatchDecision decision) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));

        // The naive fallback must never clobber a semantic result that has
        // already landed (MATCHED) or is still in flight (PROCESSING — backend-node
        // holds the claim). Checked here, inside the write transaction, so it is
        // immune to the gap between the fallback's own status read and this call.
        if (decision.matcher() == Matcher.NAIVE
                && (complaint.getMatchingStatus() == MatchingStatus.MATCHED
                        || complaint.getMatchingStatus() == MatchingStatus.PROCESSING)) {
            log.info("Ignoring naive fallback for complaint {}: already {} — semantic wins",
                    complaintId, complaint.getMatchingStatus());
            Incident current = complaint.getIncident();
            return new AttachResponse(
                    current == null ? null : String.valueOf(current.getId()),
                    false,
                    current == null ? null : current.getPriorityScore(),
                    Wire.enumValue(complaint.getMatchingStatus()));
        }

        Long previousIncidentId =
                complaint.getIncident() == null ? null : complaint.getIncident().getId();

        boolean created = false;
        Incident incident;
        if (decision.incidentId() == null) {
            incident = createFrom(complaint);
            created = true;
        } else {
            incident = incidentRepository.findById(decision.incidentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));

            // A cross-ward match is a bug in whoever decided it. Refuse loudly
            // rather than absorbing it — a complaint filed into another ward's
            // incident is invisible to the officer who owns it.
            Long incidentWardId = incident.getWard() == null ? null : incident.getWard().getId();
            Long complaintWardId = complaint.getWard() == null ? null : complaint.getWard().getId();
            if (incidentWardId == null || !incidentWardId.equals(complaintWardId)) {
                throw new ValidationException("Complaint and incident belong to different wards");
            }
        }

        boolean moved = previousIncidentId != null && !previousIncidentId.equals(incident.getId());

        complaint.setIncident(incident);
        complaint.setMatchingStatus(decision.matcher() == Matcher.SEMANTIC
                ? MatchingStatus.MATCHED
                : MatchingStatus.DEGRADED);
        complaint.setMatchedAt(LocalDateTime.now());
        complaintRepository.save(complaint);

        recompute(incident);

        if (moved) {
            // The complaint left previousIncident (a reconcile run correcting a
            // naive grouping). Recompute it; if it is now empty — a single-complaint
            // naive incident — delete it so it does not sit in the officer queue
            // at priority 0.
            Incident old = incidentRepository.findById(previousIncidentId).orElse(null);
            if (old != null) {
                if (complaintRepository.countByIncidentId(old.getId()) == 0) {
                    incidentRepository.delete(old);
                    log.info("Removed emptied incident {} after moving complaint {} to {}",
                            old.getId(), complaintId, incident.getId());
                } else {
                    recompute(old);
                }
            }
        }

        MatchOutcome outcome = moved ? MatchOutcome.RECONCILED
                : created ? MatchOutcome.CREATED
                : MatchOutcome.MATCHED;
        matchLogRepository.save(IncidentMatchLog.builder()
                .complaintId(complaintId)
                .decision(outcome)
                // Recorded separately because RECONCILED masks it: a move into a
                // new incident and a move into an existing one share that value,
                // and telling them apart is the join-vs-split signal Phase 8 needs.
                .created(created)
                .matcher(decision.matcher())
                .chosenIncidentId(incident.getId())
                .previousIncidentId(moved ? previousIncidentId : null)
                .topSiblingComplaintId(decision.topSiblingComplaintId())
                .topSimilarity(decision.topSimilarity())
                .candidateCount(decision.candidateCount() == null ? 0 : decision.candidateCount())
                .threshold(decision.threshold())
                .model(decision.model())
                .embeddingDim(decision.embeddingDim())
                .build());

        log.info("Attached complaint {} to incident {} (outcome={}, matcher={}, similarity={})",
                complaintId, incident.getId(), outcome, decision.matcher(), decision.topSimilarity());

        return new AttachResponse(
                String.valueOf(incident.getId()), created, incident.getPriorityScore(),
                Wire.enumValue(complaint.getMatchingStatus()));
    }

    /**
     * Recomputes one incident by id, without touching membership.
     *
     * Deliberately NOT routed through {@link #attach}: attach records a matching
     * <em>decision</em> — it stamps {@code matching_status} and writes an
     * {@code incident_match_log} row for the Phase 8 evaluation. Re-deriving a
     * score is none of those things, and reusing attach for it would fabricate
     * "semantic match" rows that never happened and corrupt that dataset.
     *
     * <p>Needed because the age term of the priority formula is time-dependent,
     * so a stored score drifts with no writes at all (see {@code PriorityService}).
     * Exposed as {@code POST /internal/incidents/{id}/recompute}.
     */
    @Transactional
    public Incident recomputeById(Long incidentId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
        recompute(incident);
        return incident;
    }

    /**
     * Recomputes membership-derived fields: count, centroid, priority, reasons.
     *
     * Public because merge and unlink will need exactly this once they exist.
     */
    @Transactional
    public void recompute(Incident incident) {
        List<Complaint> members =
                complaintRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId());

        // Counted from scratch, never count+1, so a retried callback cannot
        // inflate the total.
        incident.setComplaintCount(members.size());

        applyCentroid(incident, members);

        // The centroid moves as members join, so the address is re-derived with
        // it. Nearly always a cache hit: the mean of a tight cluster barely
        // shifts, so it keeps rounding to the same key.
        if (incident.getLatitude() != null && incident.getLongitude() != null) {
            geocodingService
                    .reverseGeocode(incident.getLatitude(), incident.getLongitude())
                    .ifPresent(incident::setAddress);
        }

        PriorityResult priority = priorityService.compute(incident, members, LocalDateTime.now());
        incident.setPriorityScore(priority.score());
        incident.setPriorityReasons(priority.reasons());

        incidentRepository.save(incident);
    }

    private Incident createFrom(Complaint complaint) {
        Incident incident = Incident.builder()
                .ward(complaint.getWard())
                .category(complaint.getCategory())
                .title(complaint.getTitle())
                .status(IncidentStatus.OPEN)
                .latitude(complaint.getLatitude())
                .longitude(complaint.getLongitude())
                .complaintCount(1)
                .priorityScore(0.0)
                .build();
        return incidentRepository.save(incident);
    }

    /** Mean of member coordinates — good enough at city scale. */
    private static void applyCentroid(Incident incident, List<Complaint> members) {
        double latSum = 0;
        double lonSum = 0;
        int counted = 0;

        for (Complaint member : members) {
            if (member.getLatitude() != null && member.getLongitude() != null) {
                latSum += member.getLatitude();
                lonSum += member.getLongitude();
                counted++;
            }
        }

        if (counted > 0) {
            incident.setLatitude(latSum / counted);
            incident.setLongitude(lonSum / counted);
        }
    }
}
