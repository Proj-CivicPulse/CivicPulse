package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.config.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds the httpOnly auth cookies.
 *
 * WHY COOKIES, NOT A BEARER TOKEN IN THE RESPONSE BODY:
 * the frontend deliberately never stores tokens in JS-reachable storage —
 * see the comment in frontend/src/stores/auth.store.ts. An httpOnly cookie
 * is unreadable from JavaScript, so an XSS bug cannot exfiltrate the
 * session. frontend/src/services/api.ts already sends every request with
 * {@code credentials: 'include'} for exactly this design.
 *
 * CSRF: cookies are SameSite=Lax, so a cross-site form post or image tag
 * will not carry them, which is why {@code csrf().disable()} in
 * SecurityConfig is safe here. If a future endpoint ever needs
 * SameSite=None (a genuinely cross-origin browser client), CSRF tokens
 * must be re-enabled at the same time.
 *
 * Path is "/" rather than something narrow like "/auth/refresh" because the
 * browser matches Path against the URL IT sees — which is the proxy path
 * ({@code /api/core/...}), not this service's own route. Scoping to the
 * backend's path would silently stop the cookie from ever being sent.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    public static final String ACCESS_COOKIE = "cp_access_token";
    public static final String REFRESH_COOKIE = "cp_refresh_token";

    private final AppProperties appProperties;
    private final JwtProperties jwtProperties;

    private ResponseCookie build(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(appProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie accessCookie(String token) {
        return build(ACCESS_COOKIE, token, Duration.ofMillis(jwtProperties.getAccessExpiration()));
    }

    public ResponseCookie refreshCookie(String token) {
        return build(REFRESH_COOKIE, token, Duration.ofMillis(jwtProperties.getRefreshExpiration()));
    }

    /** Same attributes, empty value, maxAge 0 — the only reliable way to clear a cookie. */
    public ResponseCookie clearAccessCookie() {
        return build(ACCESS_COOKIE, "", Duration.ZERO);
    }

    public ResponseCookie clearRefreshCookie() {
        return build(REFRESH_COOKIE, "", Duration.ZERO);
    }

    public static Optional<String> readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
