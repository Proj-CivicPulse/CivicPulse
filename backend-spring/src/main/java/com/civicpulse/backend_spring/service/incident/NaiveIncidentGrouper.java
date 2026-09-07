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
 * Placeholder complaint-to-incident grouping: same ward, same category, still
 * active, opened recently, and geographically close.
 *
 * THIS IS NOT SEMANTIC MATCHING, and it is named so nobody mistakes it for it.
 * Phase 2 replaces the decision with embedding similarity computed in Node;
 * what it does not replace is IncidentAttachmentService, which performs the
 * write either way.
 *
 * Why run it at all before Phase 2: without incidents, GET /incidents,
 * /incidents/{id}, /incidents/{id}/complaints, PATCH /incidents/{id}, both
 * dashboard endpoints, the public ward strip, and the entire priority engine
 * have no rows to exercise. Shipping those endpoints with nothing but empty
 * arrays behind them means shipping them unverified.
 *
 * It also gives Phase 8 its baseline for free: "semantic matching vs. a naive
 * ward+category control" is exactly the comparison the paper needs, and this
 * is that control.
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

    /**
     * @return the incident this complaint should join, or empty to start a new one
     */
    public Optional<Incident> findMatch(Complaint complaint) {
        if (!isEnabled() || complaint.getWard() == null) {
            return Optional.empty();
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

        return match;
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
