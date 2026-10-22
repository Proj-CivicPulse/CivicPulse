package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.incident.GroupingDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.MembershipChangeDto;
import com.civicpulse.backend_spring.dto.incident.MembershipChangeRequest;
import com.civicpulse.backend_spring.dto.incident.RecommendationDto;
import com.civicpulse.backend_spring.dto.incident.RecommendationFeedbackRequest;
import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.service.incident.IncidentQuery;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.service.incident.GroupingExplainer;
import com.civicpulse.backend_spring.service.incident.IncidentMembershipService;
import com.civicpulse.backend_spring.service.incident.IncidentService;
import com.civicpulse.backend_spring.service.intelligence.RecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The officer queue. The whole /incidents prefix is already OFFICER-gated in
 * SecurityConfig, so no per-method annotation is needed here.
 */
@RestController
@RequestMapping("/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;
    private final RecommendationService recommendationService;
    private final IncidentMembershipService membershipService;
    private final GroupingExplainer groupingExplainer;

    /**
     * One page of the queue, most urgent first by default
     * (docs/api-contract.md, E1). Every filter is repeatable — OR within a
     * filter, AND across filters — and {@code from}/{@code to} select incidents
     * with a member REPORTED in the window.
     *
     * <p>{@code minPriority} is kept for one release so an older client keeps
     * working; {@code priorityBand} replaces it.
     */
    @GetMapping
    public ResponseEntity<PageResponse<IncidentDto>> list(
            @RequestParam(required = false) List<Long> wardId,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> priorityBand,
            @RequestParam(required = false) Double minPriority,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {

        return ResponseEntity.ok(incidentService.search(IncidentQuery.parse(
                wardId, category, status, priorityBand, minPriority,
                from, to, q, sort, dir, limit, offset)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(incidentService.getById(id));
    }

    @GetMapping("/{id}/complaints")
    public ResponseEntity<List<ComplaintDto>> complaints(@PathVariable Long id) {
        return ResponseEntity.ok(incidentService.getComplaints(id));
    }

    /** Why each report is part of this incident, in an officer's terms. */
    @GetMapping("/{id}/grouping")
    public ResponseEntity<GroupingDto> grouping(@PathVariable Long id) {
        return ResponseEntity.ok(groupingExplainer.explain(id));
    }

    /** Merge this incident into {@code intoIncidentId}: same ward, same category, both active. */
    @PostMapping("/{id}/merge")
    public ResponseEntity<MembershipChangeDto> merge(
            @PathVariable Long id,
            @RequestBody MembershipChangeRequest request,
            Authentication authentication) {
        Long into = request.getIntoIncidentId() == null || request.getIntoIncidentId().isBlank()
                ? null : parseId(request.getIntoIncidentId());
        return ResponseEntity.ok(membershipService.merge(id, into, officer(authentication), request.getNote()));
    }

    /** Split one report off into an incident of its own: "not the same problem". */
    @PostMapping("/{id}/complaints/{complaintId}/unlink")
    public ResponseEntity<MembershipChangeDto> unlink(
            @PathVariable Long id,
            @PathVariable Long complaintId,
            @RequestBody(required = false) MembershipChangeRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(membershipService.unlink(
                id, complaintId, officer(authentication), request == null ? null : request.getNote()));
    }

    private static long officer(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return userId;
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            throw new com.civicpulse.backend_spring.exception.ValidationException("intoIncidentId must be a valid id");
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<IncidentDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateIncidentRequest request) {

        return ResponseEntity.ok(incidentService.update(id, request));
    }

    /** Phase 11: the recommended next action, the rule that fired, and its facts. */
    @GetMapping("/{id}/recommendation")
    public ResponseEntity<RecommendationDto> recommendation(@PathVariable Long id) {
        return ResponseEntity.ok(recommendationService.recommend(id));
    }

    /**
     * Phase 11.3: the officer accepts or overrides the current recommendation.
     * Logged only — the incident itself is not changed.
     */
    @PostMapping("/{id}/recommendation/feedback")
    public ResponseEntity<RecommendationDto> recommendationFeedback(
            @PathVariable Long id,
            @Valid @RequestBody RecommendationFeedbackRequest request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recommendationService.feedback(id, userId, request));
    }
}
