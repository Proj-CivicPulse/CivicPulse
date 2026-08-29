package com.civicpulse.backend_spring.config;

import com.civicpulse.backend_spring.exception.ApiError;
import com.civicpulse.backend_spring.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
// Spring Boot 4 ships Jackson 3, whose ObjectMapper lives under
// `tools.jackson`. The old `com.fasterxml.jackson.databind.ObjectMapper` is
// still on the classpath transitively (via jjwt-jackson) but no bean of
// that type exists — importing it fails context startup.
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Spring Security rejects requests inside the filter chain, before
 * {@code @RestControllerAdvice} can see them — so 401/403 would otherwise
 * come back in Spring's default body instead of the shared contract shape.
 * These two handlers keep every response consistent.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorHandlers {

    private final ObjectMapper objectMapper;

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message));
    }

    /** No (or unusable) credentials on a protected endpoint → 401. */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
                write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                        "Authentication required");
    }

    /** Authenticated, but the wrong role (e.g. a citizen hitting an officer route) → 403. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                        "You do not have permission to access this resource");
    }
}
