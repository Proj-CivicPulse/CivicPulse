package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.complaint.CreateComplaintRequest;
import com.civicpulse.backend_spring.dto.complaint.UpdateComplaintRequest;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.service.complaint.ComplaintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    /**
     * Public. Reporting a problem does not require an account
     * (docs/endpoints.md); tracking one does.
     *
     * When the caller does happen to be signed in, the complaint is attributed
     * to them so it shows up under My reports — that is the whole incentive to
     * register.
     */
    @PostMapping
    public ResponseEntity<ComplaintDto> create(
            @Valid @RequestBody CreateComplaintRequest request,
            Authentication authentication) {

        ComplaintDto created = complaintService.create(request, userIdOrNull(authentication));

        return ResponseEntity
                .created(URI.create("/complaints/" + created.getId()))
                .body(created);
    }

    /**
     * Officer only. This returns the full text of every resident's complaint,
     * so it must not fall through to plain "authenticated".
     */
    @GetMapping
    @PreAuthorize("hasRole('OFFICER')")
    public ResponseEntity<List<ComplaintDto>> list(
            @RequestParam(required = false) Long wardId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {

        return ResponseEntity.ok(complaintService.list(
                wardId, category, Wire.parseEnum(ComplaintStatus.class, status, "status")));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<List<ComplaintDto>> mine(Authentication authentication) {
        return ResponseEntity.ok(complaintService.listForUser(requireUserId(authentication)));
    }

    /**
     * Officers read any complaint; a citizen reads only their own, and gets a
     * 404 rather than a 403 otherwise — a 403 would confirm the id exists and
     * make sequential ids enumerable.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ComplaintDto> getById(
            @PathVariable Long id, Authentication authentication) {

        return ResponseEntity.ok(complaintService.getById(
                id, requireUserId(authentication), isOfficer(authentication)));
    }

    /**
     * Officer only.
     *
     * SecurityConfig also matches this path explicitly. Both are deliberate:
     * before this endpoint existed the path fell through to
     * anyRequest().authenticated(), which would have let any signed-in citizen
     * change the status of anyone's complaint. Belt and braces on the one
     * endpoint where getting it wrong is a data-integrity hole.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OFFICER')")
    public ResponseEntity<ComplaintDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateComplaintRequest request) {

        return ResponseEntity.ok(complaintService.update(id, request));
    }

    private static Long userIdOrNull(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    private static Long requireUserId(Authentication authentication) {
        Long userId = userIdOrNull(authentication);
        if (userId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        return userId;
    }

    private static boolean isOfficer(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_OFFICER"::equals);
    }
}
