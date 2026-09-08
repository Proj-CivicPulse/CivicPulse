package com.civicpulse.backend_spring.service.matching;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import com.civicpulse.backend_spring.service.incident.MatchDecision;
import com.civicpulse.backend_spring.service.incident.NaiveIncidentGrouper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the Phase 2 matching trigger and its fallback.
 *
 * The happy path is entirely in backend-node: this service POSTs the trigger,
 * Node embeds + matches and calls {@code /internal/incidents/attach} back. This
 * class exists for everything that can go wrong on the way there — a slow Node,
 * a dead Node, an open circuit — and for the naive fallback that keeps the
 * complaint visible to officers when Node cannot answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintMatchingService {

    private final NodeMatchingClient nodeClient;
    private final MatchingCircuitBreaker circuitBreaker;
    private final ComplaintRepository complaintRepository;
    private final NaiveIncidentGrouper grouper;
    private final IncidentAttachmentService attachmentService;

    /** Fired {@code AFTER_COMMIT} for a new complaint, on the matching executor. */
    @Async("matchingExecutor")
    public void trigger(Long complaintId) {
        run(complaintId, false);
    }

    /**
     * Synchronous entry point for the reconcile sweep.
     *
     * @return {@code false} when backend-node is unreachable or the circuit is
     *         open — the sweep reads this as "stop the batch, try again next run"
     */
    public boolean run(Long complaintId, boolean force) {
        if (!circuitBreaker.allowRequest()) {
            log.debug("Matching circuit open — complaint {} goes straight to the naive fallback",
                    complaintId);
            fallbackToNaive(complaintId);
            return false;
        }

        try {
            nodeClient.process(complaintId, force);
            circuitBreaker.recordSuccess();
            return true;
        } catch (NodeConnectionException ex) {
            log.warn("backend-node unreachable for complaint {} — naive fallback", complaintId, ex);
            circuitBreaker.recordFailure();
            fallbackToNaive(complaintId);
            return false;
        } catch (NodeSlowException ex) {
            // Node takes the PROCESSING claim before doing anything slow, so the
            // status tells us whether it actually picked the request up.
            MatchingStatus status = complaintRepository.findById(complaintId)
                    .map(Complaint::getMatchingStatus)
                    .orElse(null);
            if (status == MatchingStatus.PROCESSING || status == MatchingStatus.MATCHED) {
                log.info("backend-node slow for complaint {} (status={}) — letting its result land",
                        complaintId, status);
                circuitBreaker.recordSuccess();
                return true;
            }
            log.warn("backend-node did not pick up complaint {} before the timeout — naive fallback",
                    complaintId);
            circuitBreaker.recordFailure();
            fallbackToNaive(complaintId);
            return false;
        }
    }

    /**
     * Attaches the complaint via the naive grouper — but only if it is still
     * {@code PENDING}. A {@code PROCESSING} row means a Node run holds the claim
     * and its result must not be pre-empted; {@code MATCHED}/{@code DEGRADED}
     * means someone already handled it.
     */
    private void fallbackToNaive(Long complaintId) {
        Complaint complaint = complaintRepository.findById(complaintId).orElse(null);
        if (complaint == null) {
            return;
        }
        if (complaint.getMatchingStatus() != MatchingStatus.PENDING) {
            log.debug("Skipping naive fallback for complaint {}: status is {}",
                    complaintId, complaint.getMatchingStatus());
            return;
        }
        if (!grouper.isEnabled()) {
            log.info("Naive fallback disabled — complaint {} stays PENDING for the reconcile sweep",
                    complaintId);
            return;
        }

        try {
            NaiveIncidentGrouper.NaiveMatch match = grouper.findMatch(complaint);
            attachmentService.attach(complaintId, MatchDecision.naive(
                    match.incident() == null ? null : match.incident().getId(),
                    match.candidateCount()));
        } catch (RuntimeException ex) {
            log.error("Naive fallback failed for complaint {}; it stays PENDING", complaintId, ex);
        }
    }
}
