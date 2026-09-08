package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.util.GeoDistance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Non-semantic complaint-to-incident grouping: same ward, same category, still
 * active, opened recently, and geographically close.
 *
 * THIS IS NOT SEMANTIC MATCHING, and it is named so nobody mistakes it for it.
 *
 * <p><b>Since Phase 2 this is the resilience fallback, not the matcher.</b> The
 * real decision is embedding similarity computed in backend-node. This runs only
 * when {@code ComplaintMatchingService} cannot reach Node — connection refused,
 * a 5xx, or an open circuit breaker — and the complaint it attaches is tagged
 * {@code MatchingStatus.DEGRADED}. {@code MatchReconciliationJob} then re-runs
 * the real pipeline over those and corrects the assignment once Node recovers.
 * What Phase 2 did not change is {@link IncidentAttachmentService}, which
 * performs the write either way.
 *
 * <p>Demoted rather than deleted, deliberately. Without it, a Node outage means
 * complaints land nowhere and officers are blind to them until reconciliation
 * runs; with it, they are visible immediately and merely grouped worse.
 *
 * <p>It also gives Phase 8 its baseline for free: "semantic matching vs. a naive
 * ward+category control" is exactly the comparison the paper needs, and this is
 * that control. Better still, every time reconciliation moves a naive-grouped
 * complaint, that is a labelled <em>disagreement</em> between the two — recorded
 * as a {@code RECONCILED} row in {@code incident_match_log}.
 *
 * KNOWN AND ACCEPTED WEAKNESS: this will produce bad merges. Two unrelated
 * potholes 400 m apart become one incident. The three bounds below (active
 * status, time window, distance) keep that from degenerating into "every
 * pothole in the ward, forever", but they do not make it correct. Every
 * decision is logged at INFO so Phase 8 can measure exactly how wrong it was.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NaiveIncidentGrouper {

    private static final List<IncidentStatus> ACTIVE =
            List.of(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);

    private final IncidentRepository incidentRepository;
    private final AppProperties appProperties;

    public boolean isEnabled() {
        return "naive".equalsIgnoreCase(appProperties.getIncidentGrouping().getStrategy());
    }

    /** The fallback's decision: the incident to join (or null to create), and how many candidates it weighed. */
    public record NaiveMatch(Incident incident, int candidateCount) {
    }

    /**
     * @return the incident this complaint should join (or {@code null} to start a
     *         new one), with the candidate count for the {@code incident_match_log} row
     */
    public NaiveMatch findMatch(Complaint complaint) {
        if (!isEnabled() || complaint.getWard() == null) {
            return new NaiveMatch(null, 0);
        }

        var config = appProperties.getIncidentGrouping();
        LocalDateTime since = LocalDateTime.now().minusDays(config.getWindowDays());

        List<Incident> candidates = incidentRepository
                .findByWardIdAndCategoryAndStatusInAndCreatedAtAfter(
                        complaint.getWard().getId(), complaint.getCategory(), ACTIVE, since);

        Optional<Incident> match = candidates.stream()
                .filter(incident -> withinRadius(incident, complaint, config.getMaxDistanceKm()))
                // Nearest wins. With no similarity signal available, proximity
                // is the only discriminator there is.
                .min(Comparator.comparingDouble(incident -> distanceKm(incident, complaint)));

        log.info("Naive grouping for complaint {}: ward={} category={} candidates={} matched={}",
                complaint.getId(),
                complaint.getWard().getId(),
                complaint.getCategory(),
                candidates.size(),
                match.map(Incident::getId).orElse(null));

        return new NaiveMatch(match.orElse(null), candidates.size());
    }

    private static boolean withinRadius(Incident incident, Complaint complaint, double maxKm) {
        if (incident.getLatitude() == null || incident.getLongitude() == null
                || complaint.getLatitude() == null || complaint.getLongitude() == null) {
            // No coordinates to compare. Ward and category alone are too weak
            // a signal to merge on, so decline rather than guess.
            return false;
        }
        return distanceKm(incident, complaint) <= maxKm;
    }

    private static double distanceKm(Incident incident, Complaint complaint) {
        return GeoDistance.kilometresBetween(
                incident.getLatitude(), incident.getLongitude(),
                complaint.getLatitude(), complaint.getLongitude());
    }
}
