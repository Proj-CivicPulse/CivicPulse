package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.auth.AuthResponse;
import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.dto.auth.RegisterRequest;
import com.civicpulse.backend_spring.dto.auth.UserDto;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.service.auth.AuthCookieFactory;
import com.civicpulse.backend_spring.service.auth.AuthService;
import com.civicpulse.backend_spring.service.auth.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session endpoints. All four are consumed by
 * frontend/src/services/auth.service.ts and frontend/src/services/api.ts.
 *
 * Tokens are set as httpOnly cookies and never appear in a response body —
 * see AuthCookieFactory for the reasoning.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final AuthCookieFactory cookieFactory;

    /** Issues a fresh token pair and returns the user. */
    private ResponseEntity<AuthResponse> respondWithSession(User user, HttpStatus status) {
        String role = user.getRole().name();
        ResponseCookie access = cookieFactory.accessCookie(
                jwtService.generateAccessToken(user.getId(), role));
        ResponseCookie refresh = cookieFactory.refreshCookie(
                jwtService.generateRefreshToken(user.getId(), role));

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, access.toString())
                .header(HttpHeaders.SET_COOKIE, refresh.toString())
                .body(new AuthResponse(UserDto.from(user)));
    }

    /** POST /auth/register — always creates a citizen. Logs the new user straight in. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return respondWithSession(user, HttpStatus.CREATED);
    }

    /** POST /auth/login */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = authService.authenticate(request);
        return respondWithSession(user, HttpStatus.OK);
    }

    /**
     * GET /auth/me — session restore on every app boot
     * (frontend/src/stores/auth.store.ts checkAuth).
     *
     * Returns 401 when unauthenticated, which the frontend expects and
     * handles as "logged out" rather than as an error.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return ResponseEntity.ok(UserDto.from(authService.requireById(userId)));
    }

    /**
     * POST /auth/refresh — exchanges a valid refresh cookie for a new token
     * pair (the refresh token is rotated, not reused).
     *
     * Public by design: the access token is expected to be expired here, so
     * requiring one would defeat the purpose.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        Claims claims = AuthCookieFactory
                .readCookie(request, AuthCookieFactory.REFRESH_COOKIE)
                .flatMap(token -> jwtService.parse(token, JwtService.TYPE_REFRESH))
                .orElseThrow(() -> new UnauthorizedException("Refresh token is missing or invalid"));

        User user = authService.requireById(jwtService.extractUserId(claims));
        return respondWithSession(user, HttpStatus.OK);
    }

    /**
     * POST /auth/logout — clears both cookies.
     *
     * Always 204, even without a session: logout is idempotent, and the
     * frontend calls it fire-and-forget while already clearing local state.
     * Note that tokens are stateless, so a token copied elsewhere stays
     * valid until it expires (see JwtService).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clearAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clearRefreshCookie().toString())
                .build();
    }
}
