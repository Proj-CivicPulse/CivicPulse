package com.civicpulse.backend_spring.dto.incident;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class IncidentDto {

    private final String id;
    private final String wardId;
    private final String departmentId;
    private final String title;
    private final String summary;
    private final String category;

    private final Double priorityScore;

    /**
     * Derived from priorityScore by PriorityBand, and shipped so the dashboard,
     * the map markers, and Node's Copilot all agree on where the cut-offs are
     * without each keeping a private copy.
     */
    private final String priorityBand;

    /**
     * The explainability payload. Human-readable evidence strings, never codes.
     * Empty is a legitimate value and clients must render it as "no
     * justification recorded" rather than hiding the section.
     */
    private final List<String> priorityReasons;

    private final String status;
    /** Human-readable location of the centroid. Null when geocoding is disabled. */
    private final String address;
    private final int complaintCount;
    private final Double lat;

    @JsonProperty("long")
    private final Double lng;

    /**
     * Written by Node in Phase 6, null until then. Present in the schema since
     * V1 but absent from the original api-contract.md Incident shape — the doc
     * is being corrected alongside this.
     */
    private final String aiRecommendation;

    private final String createdAt;
    private final String updatedAt;

    public static IncidentDto from(Incident incident) {
        double score = incident.getPriorityScore() == null ? 0.0 : incident.getPriorityScore();

        return new IncidentDto(
                Wire.id(incident.getId()),
                incident.getWard() == null ? null : Wire.id(incident.getWard().getId()),
                incident.getDepartment() == null ? null : Wire.id(incident.getDepartment().getId()),
                incident.getTitle(),
                incident.getSummary(),
                incident.getCategory(),
                score,
                PriorityBand.of(score).wireValue(),
                incident.getPriorityReasons() == null ? List.of() : incident.getPriorityReasons(),
                Wire.enumValue(incident.getStatus()),
                incident.getAddress(),
                incident.getComplaintCount() == null ? 0 : incident.getComplaintCount(),
                incident.getLatitude(),
                incident.getLongitude(),
                incident.getAiRecommendation(),
                Wire.timestamp(incident.getCreatedAt()),
                Wire.timestamp(incident.getUpdatedAt())
        );
    }
}
