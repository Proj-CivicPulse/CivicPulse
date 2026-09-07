package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.internal.AttachRequest;
import com.civicpulse.backend_spring.dto.internal.AttachResponse;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import com.civicpulse.backend_spring.service.ward.WardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service routes, called by backend-node. Never reachable from a
 * browser: gated on the X-Internal-Token header, which fails closed when the
 * secret is unset (see InternalTokenAuthorizationManager).
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final IncidentAttachmentService attachmentService;
    private final ComplaintRepository complaintRepository;
    private final WardService wardService;

    /**
     * Node decides the match; Spring performs the write, in one transaction.
     *
     * Named /incidents/attach rather than the sub-collection form
     * /incidents/{id}/complaints that docs/endpoints.md originally listed,
     * because incidentId may legitimately be null ("start a new incident") and
     * a null cannot occupy a path segment. This is an RPC-shaped command and is
     * named honestly as one.
     */
    @PostMapping("/incidents/attach")
    public ResponseEntity<AttachResponse> attach(@Valid @RequestBody AttachRequest request) {
        Long complaintId = parseId(request.getComplaintId(), "complaintId");
        Long incidentId = request.getIncidentId() == null || request.getIncidentId().isBlank()
                ? null
                : parseId(request.getIncidentId(), "incidentId");

        return ResponseEntity.ok(
                attachmentService.attach(complaintId, incidentId, request.getSimilarity()));
    }

    /** Full complaint context, for embedding generation and Copilot grounding. */
    @GetMapping("/complaints/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<ComplaintDto> complaint(@PathVariable Long id) {
        return ResponseEntity.ok(complaintRepository.findById(id)
                .map(ComplaintDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found")));
    }

    /** Ward metadata, for Node's candidate pre-filter. */
    @GetMapping("/wards/{id}")
    public ResponseEntity<WardDto> ward(@PathVariable Long id) {
        return ResponseEntity.ok(wardService.getById(id));
    }

    private static Long parseId(String value, String field) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ValidationException(field + " must be a valid id");
        }
    }
}
