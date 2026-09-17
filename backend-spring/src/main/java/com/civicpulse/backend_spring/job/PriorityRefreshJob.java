package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps stored priority scores from drifting as incidents age.
 *
 * <p>The priority formula has a time-dependent term — age, saturating at 14
 * days — so an incident's correct score changes every day whether or not
 * anything is written to it. Until now recompute happened only on a membership
 * change (attach, reconcile, unlink), which meant an incident that stopped
 * attracting new reports also stopped ageing: exactly the "small old incident
 * eventually surfaces" fairness the 20% age weight exists to provide, quietly
 * not happening. {@code PriorityService} carries that as a KNOWN STALENESS
 * note; this is the sweep it asks for.
 *
 * <p><b>What it deliberately is not.</b> It never touches membership, status,
 * or {@code incident_match_log}. Re-deriving a score is not a matching
 * decision, and routing it through the attach path would fabricate match rows
 * for events that never happened and corrupt the Phase 8 evaluation dataset —
 * which is why {@link IncidentAttachmentService#recomputeById(Long)} exists
 * separately from attach.
 *
 * <p>Only OPEN and IN_PROGRESS incidents are swept. A resolved incident's score
 * records how urgent the problem was while it was live; ageing it further would
 * rewrite history for no reader.
 *
 * <p>Each incident is recomputed in its OWN transaction. One bad row — a
 * geocoding failure, a concurrent delete — then costs that row and not the rest
 * of the batch.
 *
 * <p>Disabled by {@code app.priority.refresh.enabled=false}.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.priority.refresh",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PriorityRefreshJob {

    /** An incident is only queued while it still needs attention. */
    private static final List<IncidentStatus> ACTIVE =
            List.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);

    private final IncidentRepository incidentRepository;
    private final IncidentAttachmentService attachmentService;
    private final AppProperties appProperties;

    @Scheduled(
            fixedDelayString = "${app.priority.refresh.interval-ms:300000}",
            initialDelayString = "${app.priority.refresh.interval-ms:300000}")
    public void sweep() {
        var config = appProperties.getPriority().getRefresh();
        LocalDateTime staleBefore =
                LocalDateTime.now().minusMinutes(config.getStaleAfterMinutes());

        List<Incident> stale = incidentRepository.findStalePriority(
                ACTIVE, staleBefore, PageRequest.of(0, config.getBatchSize()));

        if (stale.isEmpty()) {
            return;
        }

        int refreshed = 0;
        int failed = 0;
        for (Incident incident : stale) {
            try {
                attachmentService.recomputeById(incident.getId());
                refreshed++;
            } catch (RuntimeException ex) {
                // Including the incident that vanished between the query and
                // the recompute, which is a normal race, not an error worth
                // stopping the sweep for.
                failed++;
                log.warn("Priority refresh failed for incident {}: {}",
                        incident.getId(), ex.toString());
            }
        }

        log.info("Priority refresh: {} recomputed, {} failed, {} older than {}",
                refreshed, failed, stale.size(), staleBefore);
    }
}
