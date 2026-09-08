package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.matching.ComplaintMatchingService;
import com.civicpulse.backend_spring.service.matching.MatchingCircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes the loop after a backend-node outage.
 *
 * Complaints that could not be matched semantically sit as {@code PENDING} (no
 * grouping at all) or {@code DEGRADED} (naive fallback) — plus the odd
 * {@code PROCESSING} row whose Node run crashed. Once Node is answering again
 * this sweep re-runs the real pipeline over them, oldest first, and the attach
 * path corrects any naive mis-grouping (a {@code RECONCILED} log row).
 *
 * Disabled by {@code app.matching.reconciliation.enabled=false} (the context
 * test does this so no sweep fires at a non-existent Node).
 */
@Component
@ConditionalOnProperty(
        prefix = "app.matching.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MatchReconciliationJob {

    private final ComplaintRepository complaintRepository;
    private final ComplaintMatchingService matchingService;
    private final MatchingCircuitBreaker circuitBreaker;
    private final AppProperties appProperties;

    @Scheduled(
            fixedDelayString = "${app.matching.reconciliation.interval-ms:60000}",
            initialDelayString = "${app.matching.reconciliation.interval-ms:60000}")
    public void sweep() {
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(appProperties.getMatching().getProcessingStaleSeconds());

        long backlog = complaintRepository.countReconcileBacklog(staleBefore);
        if (backlog == 0) {
            return;
        }

        // The demo-outage signal: whatever else happens, the backlog size is
        // logged every run it is non-zero, so it cannot pile up unseen.
        if (!circuitBreaker.allowRequest()) {
            log.warn("Reconcile sweep held — matching circuit is open; {} complaints waiting", backlog);
            return;
        }

        int batchSize = appProperties.getMatching().getReconciliation().getBatchSize();
        List<Complaint> batch =
                complaintRepository.findReconcileCandidates(staleBefore, PageRequest.of(0, batchSize));

        log.info("Reconcile sweep: retrying {} of {} unmatched complaints", batch.size(), backlog);

        int done = 0;
        for (Complaint complaint : batch) {
            if (!matchingService.run(complaint.getId(), true)) {
                log.warn("Reconcile sweep stopped after {} — backend-node unreachable; "
                        + "{} complaints still waiting", done, backlog - done);
                return;
            }
            done++;
        }
    }
}
