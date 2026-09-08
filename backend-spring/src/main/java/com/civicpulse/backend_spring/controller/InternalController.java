package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.internal.AttachRequest;
import com.civicpulse.backend_spring.dto.internal.AttachResponse;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.ward.WardDto;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.service.incident.IncidentAttachmentService;
import com.civicpulse.backend_spring.service.incident.MatchDecision;
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
        Long incidentId = blankToNull(request.getIncidentId()) == null
                ? null
                : parseId(request.getIncidentId(), "incidentId");
        Long topSiblingId = blankToNull(request.getTopSiblingComplaintId()) == null
                ? null
                : parseId(request.getTopSiblingComplaintId(), "topSiblingComplaintId");

        // Only backend-node calls this endpoint, and only for semantic decisions —
        // the naive fallback attaches in-process, never over the wire.
        MatchDecision decision = MatchDecision.semantic(
                incidentId,
                request.getTopSimilarity(),
                topSiblingId,
                request.getCandidateCount(),
                request.getThreshold(),
                request.getModel(),
                request.getEmbeddingDim());

        return ResponseEntity.ok(attachmentService.attach(complaintId, decision));
    }

    /**
     * Re-derives an incident's membership-derived fields — count, centroid,
     * address, priority score and reasons — without changing membership.
     *
     * Separate from /incidents/attach on purpose. attach records a matching
     * DECISION: it stamps matching_status and writes an incident_match_log row
     * for the Phase 8 evaluation. Recomputing a score is neither, and routing it
     * through attach would invent "semantic match" rows that never happened.
     *
     * The age term of the priority formula is time-dependent, so a stored score
     * drifts with no writes at all. Used by scripts/seed-dev-data.mjs --rescore,
     * and the natural hook for the scheduled refresh that is still a follow-up.
     */
    @PostMapping("/incidents/{id}/recompute")
    public ResponseEntity<IncidentDto> recompute(@PathVariable Long id) {
        return ResponseEntity.ok(IncidentDto.from(attachmentService.recomputeById(id)));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
