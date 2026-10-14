package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.service.incident.IncidentQuery;
import com.civicpulse.backend_spring.service.incident.IncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @PatchMapping("/{id}")
    public ResponseEntity<IncidentDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateIncidentRequest request) {

        return ResponseEntity.ok(incidentService.update(id, request));
    }
}
