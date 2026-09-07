package com.civicpulse.backend_spring.dto.complaint;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.entity.Complaint;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Wire shape per docs/api-contract.md, plus referenceNo and
 * incidentComplaintCount.
 *
 * Deliberately carries no denormalised wardName. Keeping the DTO to ids means
 * the lazy @ManyToOne associations are satisfied from the proxy identifier and
 * no fetch join is needed — denormalised names on every DTO is how contracts
 * rot and how N+1 gets in. Clients join against GET /wards, which is four
 * cacheable rows.
 */
@Getter
@AllArgsConstructor
public class ComplaintDto {

    private final String id;
    private final String referenceNo;
    /** null when submitted anonymously. */
    private final String userId;
    private final String wardId;
    /** null until the matcher attaches it. */
    private final String incidentId;

    /**
     * Total complaints in this one's incident, including this one. null when
     * unmatched.
     *
     * Carried here rather than left to GET /incidents/{id} because that
     * endpoint is officer-only: a citizen cannot read incidents at all, so
     * this is the only route by which "Grouped with N other reports" can reach
     * the My reports screen. It is an aggregate count and discloses nothing
     * else about the incident.
     */
    private final Integer incidentComplaintCount;

    /** null until an officer triages it. */
    private final String departmentId;
    private final String title;
    private final String description;
    private final String category;
    private final Double lat;

    @JsonProperty("long")
    private final Double lng;

    private final String status;
    /** Human-readable location. Null when geocoding is disabled. */
    private final String address;
    private final String photoUrl;
    private final String createdAt;
    private final String updatedAt;

    public static ComplaintDto from(Complaint complaint, Integer incidentComplaintCount) {
        return new ComplaintDto(
                Wire.id(complaint.getId()),
                complaint.getReferenceNo(),
                complaint.getUser() == null ? null : Wire.id(complaint.getUser().getId()),
                complaint.getWard() == null ? null : Wire.id(complaint.getWard().getId()),
                complaint.getIncident() == null ? null : Wire.id(complaint.getIncident().getId()),
                incidentComplaintCount,
                complaint.getDepartment() == null ? null : Wire.id(complaint.getDepartment().getId()),
                complaint.getTitle(),
                complaint.getDescription(),
                complaint.getCategory(),
                complaint.getLatitude(),
                complaint.getLongitude(),
                Wire.enumValue(complaint.getStatus()),
                complaint.getAddress(),
                complaint.getPhotoUrl(),
                Wire.timestamp(complaint.getCreatedAt()),
                Wire.timestamp(complaint.getUpdatedAt())
        );
    }

    /** For contexts that do not need the group size, e.g. an incident's own report list. */
    public static ComplaintDto from(Complaint complaint) {
        return from(complaint, null);
    }
}
