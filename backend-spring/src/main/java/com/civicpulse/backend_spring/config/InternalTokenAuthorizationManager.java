package com.civicpulse.backend_spring.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * Guards /internal/* — the routes backend-node calls to hand its matching
 * decisions back to Spring.
 *
 * Node holds no user session, so these cannot be role-gated. A shared secret in
 * a header is enough here because a browser cannot set a custom header
 * cross-origin without a preflight that CORS refuses, and the token is never in
 * any JavaScript this project ships.
 *
 * FAILS CLOSED. A blank configured token denies everything rather than
 * accepting everything — an unset secret must break the integration loudly, not
 * open a door quietly. It is not @NotBlank on AppProperties only because that
 * would break every existing local .env at startup for no safety gain over
 * this.
 *
 * The comparison is constant-time: a byte-by-byte early exit leaks the token
 * one character at a time to anyone who can measure response latency.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalTokenAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String HEADER = "X-Internal-Token";

    private final AppProperties appProperties;

    @Override
    public AuthorizationDecision authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {

        String configured = appProperties.getInternalToken();

        if (configured == null || configured.isBlank()) {
            log.warn("Rejecting {} — app.internal-token is not configured",
                    context.getRequest().getRequestURI());
            return new AuthorizationDecision(false);
        }

        String presented = context.getRequest().getHeader(HEADER);
        if (presented == null) {
            return new AuthorizationDecision(false);
        }

        boolean matches = MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));

        return new AuthorizationDecision(matches);
    }
}
