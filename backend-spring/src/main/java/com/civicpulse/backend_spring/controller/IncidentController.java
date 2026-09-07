package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.enums.IncidentStatus;
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

    /** Sorted most urgent first — that ordering is the queue's whole purpose. */
    @GetMapping
    public ResponseEntity<List<IncidentDto>> list(
            @RequestParam(required = false) Long wardId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Double minPriority) {

        return ResponseEntity.ok(incidentService.list(
                wardId,
                category,
                Wire.parseEnum(IncidentStatus.class, status, "status"),
                minPriority));
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
