package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.copilot.CopilotAnswerDto;
import com.civicpulse.backend_spring.dto.copilot.CopilotQueryRequest;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.service.copilot.CopilotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The officer Copilot. OFFICER-gated by the /copilot/** matcher in
 * SecurityConfig; who is asking comes from the session, never the body.
 * Contract: docs/api-contract.md, "Copilot".
 */
@RestController
@RequestMapping("/copilot")
@RequiredArgsConstructor
public class CopilotController {

    private final CopilotService copilotService;

    @PostMapping("/query")
    public ResponseEntity<CopilotAnswerDto> query(
            @Valid @RequestBody CopilotQueryRequest request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return ResponseEntity.ok(copilotService.ask(userId, request));
    }
}
