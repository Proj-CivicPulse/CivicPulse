package com.civicpulse.backend_spring.service.complaint;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.complaint.CreateComplaintRequest;
import com.civicpulse.backend_spring.dto.complaint.UpdateComplaintRequest;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Department;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import com.civicpulse.backend_spring.repository.UserRepository;
import com.civicpulse.backend_spring.repository.WardRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import com.civicpulse.backend_spring.service.incident.NaiveIncidentGrouper;
import com.civicpulse.backend_spring.service.geocoding.GeocodingService;
import com.civicpulse.backend_spring.service.ward.WardResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final WardRepository wardRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final WardResolver wardResolver;
    private final GeocodingService geocodingService;
    private final ReferenceNumberService referenceNumberService;
    private final NaiveIncidentGrouper grouper;
    private final IncidentAttachmentService attachmentService;

    /**
     * @param userId null for an anonymous submission, which is the settled
     *               decision in docs/endpoints.md — reporting is open, tracking
     *               needs an account
     */
    @Transactional
    public ComplaintDto create(CreateComplaintRequest request, Long userId) {
        Ward ward = resolveWard(request);

        Complaint complaint = Complaint.builder()
                .user(userId == null ? null : userRepository.findById(userId).orElse(null))
                .ward(ward)
                .title(request.getTitle())
                .description(request.getDescription())
                .category(request.getCategory())
                .latitude(request.getLat())
                .longitude(request.getLng())
                .photoUrl(request.getPhotoUrl())
                .status(ComplaintStatus.OPEN)
                // Best-effort: empty when geocoding is off, the point has no
                // street address, or the provider is unreachable. A report
                // without an address is still a perfectly good report.
                .address(geocodingService.reverseGeocode(request.getLat(), request.getLng())
                        .orElse(null))
                // Allocated inside this transaction, so a rollback returns the
                // number rather than burning it.
                .referenceNo(referenceNumberService.allocate(ward))
                .build();

        Complaint saved = complaintRepository.save(complaint);

        // Placeholder grouping until Phase 2. Failing to group must never fail
        // the submission: the resident's report is recorded either way, and an
        // unmatched complaint is a routine state the matcher can fix later.
        if (grouper.isEnabled()) {
            try {
                Optional<Incident> match = grouper.findMatch(saved);
                attachmentService.attach(
                        saved.getId(), match.map(Incident::getId).orElse(null), null);
            } catch (RuntimeException ex) {
                log.error("Grouping failed for complaint {}; it stays unmatched",
                        saved.getId(), ex);
            }
        }

        Complaint result = complaintRepository.findById(saved.getId()).orElseThrow();
        return toDto(result);
    }

    /**
     * Explicit wardId wins; otherwise derive it from the coordinates we already
     * require. Rejecting outright when neither works is deliberate — filing a
     * report into a guessed ward is worse than asking.
     */
    private Ward resolveWard(CreateComplaintRequest request) {
        if (request.getWardId() != null && !request.getWardId().isBlank()) {
            Long wardId = parseId(request.getWardId(), "wardId");
            return wardRepository.findById(wardId)
                    .orElseThrow(() -> new ValidationException("wardId does not match a known ward"));
        }

        return wardResolver.resolve(request.getLat(), request.getLng())
                .orElseThrow(() -> new ValidationException(
                        "wardId is required when the location does not fall inside a known ward"));
    }

    @Transactional(readOnly = true)
    public List<ComplaintDto> listForUser(Long userId) {
        return complaintRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ComplaintDto> list(Long wardId, String category, ComplaintStatus status) {
        return complaintRepository.findAll().stream()
                .filter(c -> wardId == null
                        || (c.getWard() != null && wardId.equals(c.getWard().getId())))
                .filter(c -> category == null || category.equals(c.getCategory()))
                .filter(c -> status == null || status == c.getStatus())
                .map(this::toDto)
                .toList();
    }

    /**
     * @param requesterIsOfficer officers read any complaint; a citizen reads
     *                           only their own
     */
    @Transactional(readOnly = true)
    public ComplaintDto getById(Long id, Long requesterId, boolean requesterIsOfficer) {
        Complaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));

        if (!requesterIsOfficer && !isOwnedBy(complaint, requesterId)) {
            // 404, not 403. A 403 confirms the id exists, which turns
            // sequential ids into an enumeration oracle.
            throw new ResourceNotFoundException("Complaint not found");
        }

        return toDto(complaint);
    }

    @Transactional
    public ComplaintDto update(Long id, UpdateComplaintRequest request) {
        if ((request.getStatus() == null || request.getStatus().isBlank())
                && (request.getDepartmentId() == null || request.getDepartmentId().isBlank())) {
            throw new ValidationException("At least one of status, departmentId is required");
        }

        Complaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            complaint.setStatus(
                    Wire.parseEnum(ComplaintStatus.class, request.getStatus(), "status"));
        }

        if (request.getDepartmentId() != null && !request.getDepartmentId().isBlank()) {
            Long departmentId = parseId(request.getDepartmentId(), "departmentId");
            Department department = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ValidationException(
                            "departmentId does not match a known department"));
            complaint.setDepartment(department);
        }

        return toDto(complaintRepository.save(complaint));
    }

    private static boolean isOwnedBy(Complaint complaint, Long userId) {
        User owner = complaint.getUser();
        return owner != null && userId != null && userId.equals(owner.getId());
    }

    /**
     * Adds the incident's total membership, which is what lets the My reports
     * screen say "Grouped with N other reports" without a citizen being able
     * to read incidents.
     */
    private ComplaintDto toDto(Complaint complaint) {
        Integer groupSize = complaint.getIncident() == null
                ? null
                : (int) complaintRepository.countByIncidentId(complaint.getIncident().getId());
        return ComplaintDto.from(complaint, groupSize);
    }

    private static Long parseId(String value, String field) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException(field + " must be a valid id");
        }
    }
}
