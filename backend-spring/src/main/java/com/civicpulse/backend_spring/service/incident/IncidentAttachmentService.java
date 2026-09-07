package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.internal.AttachResponse;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
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
 * whoever decided the match. This method is the body of
 * POST /internal/incidents/attach, and Phase 2 changes only WHO supplies
 * {@code incidentId} — not this code. Building it now means Phase 2 is a
 * matching algorithm rather than a matching algorithm plus a transactional
 * write path invented under deadline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentAttachmentService {

    private final ComplaintRepository complaintRepository;
    private final IncidentRepository incidentRepository;
    private final PriorityService priorityService;
    private final GeocodingService geocodingService;

    /**
     * @param incidentId null to start a new incident from this complaint
     */
    @Transactional
    public AttachResponse attach(Long complaintId, Long incidentId, Double similarity) {
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));

        boolean created = false;
        Incident incident;

        if (incidentId == null) {
            incident = createFrom(complaint);
            created = true;
        } else {
            incident = incidentRepository.findById(incidentId)
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

        complaint.setIncident(incident);
        complaintRepository.save(complaint);

        recompute(incident);

        log.info("Attached complaint {} to incident {} (created={}, similarity={})",
                complaintId, incident.getId(), created, similarity);

        return new AttachResponse(
                String.valueOf(incident.getId()), created, incident.getPriorityScore());
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
