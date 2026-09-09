package com.civicpulse.backend_spring.config;

import com.civicpulse.backend_spring.service.auth.AuthCookieFactory;
import com.civicpulse.backend_spring.service.auth.JwtService;
import com.civicpulse.backend_spring.service.auth.SessionRevocationRegistry;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates a request from its access token.
 *
 * Two accepted transports, in priority order:
 * <ol>
 *   <li>the httpOnly {@code cp_access_token} cookie — how the browser app
 *       authenticates (see {@link AuthCookieFactory} for why);</li>
 *   <li>an {@code Authorization: Bearer} header — kept for service-to-service
 *       calls (backend-node → backend-spring {@code /internal/*}) and for
 *       curl/Postman during development, neither of which has a cookie jar.</li>
 * </ol>
 *
 * A missing or invalid token is not rejected here — the filter simply
 * leaves the context unauthenticated and lets the authorization rules in
 * SecurityConfig decide, so public endpoints keep working.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionRevocationRegistry revocationRegistry;

    private Optional<String> extractToken(HttpServletRequest request) {
        Optional<String> fromCookie = AuthCookieFactory.readCookie(request, AuthCookieFactory.ACCESS_COOKIE);
        if (fromCookie.isPresent()) {
            return fromCookie;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return Optional.of(authHeader.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Don't overwrite an authentication established earlier in the chain.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            extractToken(request)
                    .flatMap(token -> jwtService.parse(token, JwtService.TYPE_ACCESS))
                    .ifPresent(claims -> authenticate(request, claims));
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        try {
            Long userId = jwtService.extractUserId(claims);
            String role = jwtService.extractRole(claims);
            if (role == null || role.isBlank()) {
                return;
            }

            // Every token issued since V9 carries its session. A null one is a
            // pre-session token that survived a deploy; refusing it costs its
            // holder one re-login and keeps "every live token is revocable" a
            // property with no exceptions.
            String sessionId = jwtService.extractSessionId(claims);
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }

            // Signed and unexpired is not enough: the session may have been
            // signed out since this token was minted. Without this check a
            // sign-out would not take effect until the access token expired.
            if (revocationRegistry.isRevoked(sessionId)) {
                return;
            }

            // Spring Security's hasRole()/@PreAuthorize expect the ROLE_ prefix.
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (NumberFormatException ex) {
            // Malformed subject — treat as unauthenticated rather than 500.
            SecurityContextHolder.clearContext();
        }
    }
}
