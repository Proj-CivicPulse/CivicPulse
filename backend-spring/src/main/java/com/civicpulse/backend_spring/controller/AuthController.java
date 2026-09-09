package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.auth.AuthResponse;
import com.civicpulse.backend_spring.dto.auth.ForgotPasswordRequest;
import com.civicpulse.backend_spring.dto.auth.LoginRequest;
import com.civicpulse.backend_spring.dto.auth.RegisterRequest;
import com.civicpulse.backend_spring.dto.auth.ResetPasswordRequest;
import com.civicpulse.backend_spring.dto.auth.UserDto;
import com.civicpulse.backend_spring.dto.auth.VerifyEmailRequest;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.RevocationReason;
import com.civicpulse.backend_spring.exception.InvalidCredentialsException;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.service.auth.AuthCookieFactory;
import com.civicpulse.backend_spring.service.auth.AuthRateLimiter;
import com.civicpulse.backend_spring.service.auth.AuthService;
import com.civicpulse.backend_spring.service.auth.EmailVerificationService;
import com.civicpulse.backend_spring.service.auth.JwtService;
import com.civicpulse.backend_spring.service.auth.PasswordResetService;
import com.civicpulse.backend_spring.service.auth.RefreshTokenService;
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

import java.util.Optional;

/**
 * Session endpoints. All of these are consumed by
 * frontend/src/services/auth.service.ts and frontend/src/services/api.ts.
 *
 * Tokens are set as httpOnly cookies and never appear in a response body —
 * see AuthCookieFactory for the reasoning. Sessions are tracked server-side
 * (RefreshTokenService), so sign-out is real revocation, not just a cleared
 * cookie.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final AuthCookieFactory cookieFactory;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    /** Writes an issued token pair as cookies and returns the user. */
    private ResponseEntity<AuthResponse> respondWithSession(
            User user, RefreshTokenService.IssuedTokens tokens, HttpStatus status) {

        ResponseCookie access = cookieFactory.accessCookie(tokens.accessToken());
        ResponseCookie refresh = cookieFactory.refreshCookie(tokens.refreshToken());

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, access.toString())
                .header(HttpHeaders.SET_COOKIE, refresh.toString())
                .body(new AuthResponse(UserDto.from(user)));
    }

    /**
     * POST /auth/register — always creates a citizen. Logs the new user
     * straight in and mails a verification link.
     *
     * Signing in immediately rather than waiting for verification is
     * deliberate; see EmailVerificationService for why the flag is not a gate.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        emailVerificationService.sendVerificationEmail(user);
        return respondWithSession(user, refreshTokenService.startSession(user), HttpStatus.CREATED);
    }

    /**
     * POST /auth/login
     *
     * The rate-limit check runs BEFORE authentication so a locked-out caller
     * never reaches the password comparison — the point is to stop spending
     * BCrypt time on guesses, not merely to hide the result.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        String ip = clientIp(httpRequest);
        rateLimiter.checkLoginAllowed(ip, request.getEmail());

        User user;
        try {
            user = authService.authenticate(request);
        } catch (InvalidCredentialsException ex) {
            rateLimiter.recordLoginFailure(ip, request.getEmail());
            throw ex;
        }

        rateLimiter.recordLoginSuccess(ip, request.getEmail());
        return respondWithSession(user, refreshTokenService.startSession(user), HttpStatus.OK);
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
        return ResponseEntity.ok(UserDto.from(authService.requireById(requireUserId(authentication))));
    }

    /**
     * POST /auth/refresh — exchanges a valid refresh cookie for a new token
     * pair. The presented token is retired, not reused; presenting it again
     * later is treated as theft and kills the session (RefreshTokenService).
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
        return respondWithSession(user, refreshTokenService.rotate(user, claims), HttpStatus.OK);
    }

    /**
     * POST /auth/logout — revokes this session and clears both cookies.
     *
     * Always 204, even without a session: logout is idempotent, and the
     * frontend calls it fire-and-forget while already clearing local state.
     * Unlike before, a token copied elsewhere is now dead the moment this
     * returns rather than living out its remaining lifetime.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessionIdOf(request).ifPresent(
                sessionId -> refreshTokenService.revokeSession(sessionId, RevocationReason.LOGOUT));
        return clearedCookies();
    }

    /**
     * POST /auth/verify-email — redeems a link from the verification email.
     *
     * Public: the whole point is that the holder may not be signed in. The
     * token is the credential, and it is single-use.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.getToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /auth/resend-verification — mails a fresh verification link.
     *
     * Authenticated, unlike the reset request below, because the address is
     * taken from the session rather than the body. That removes the whole
     * problem: nobody can aim it at an inbox they do not already control.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            Authentication authentication, HttpServletRequest httpRequest) {

        User user = authService.requireById(requireUserId(authentication));
        rateLimiter.checkAndRecordEmailRequest(clientIp(httpRequest), user.getEmail());
        emailVerificationService.sendVerificationEmail(user);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /auth/forgot-password — mails a reset link if the address has an
     * account.
     *
     * ALWAYS 204, whether or not it does. Reporting "no such account" would let
     * anyone test addresses in bulk, the same leak the login path spends a
     * whole BCrypt verification to avoid. The send is asynchronous so response
     * time does not give it away either.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {

        // Rate limited hard: this endpoint mails an address the CALLER names,
        // so unthrottled it is an email-bombing tool pointed at someone else.
        rateLimiter.checkAndRecordEmailRequest(clientIp(httpRequest), request.getEmail());
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /auth/reset-password — sets a new password from a reset link and
     * signs every session out.
     *
     * Cookies are cleared here too: the caller's own session was just revoked
     * along with the rest, so leaving stale cookies in the browser would only
     * produce a confusing 401 on their next request.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.getToken(), request.getPassword());
        return clearedCookies();
    }

    /**
     * POST /auth/logout-all — signs the user out on every device.
     *
     * Requires a live session (it is not in SecurityConfig's permitAll list),
     * because "revoke everything for user X" must be something only X can ask
     * for. This is the control to reach for if an account is believed
     * compromised: every refresh token dies immediately, and every access token
     * with it via SessionRevocationRegistry.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication authentication) {
        refreshTokenService.revokeAllForUser(requireUserId(authentication), RevocationReason.LOGOUT_ALL);
        return clearedCookies();
    }

    private ResponseEntity<Void> clearedCookies() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clearAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clearRefreshCookie().toString())
                .build();
    }

    /**
     * The session to revoke on sign-out, taken from whichever cookie is still
     * readable. The refresh cookie is preferred but the access cookie carries
     * the same sid, so sign-out still works when only one survives.
     */
    private Optional<String> sessionIdOf(HttpServletRequest request) {
        return AuthCookieFactory.readCookie(request, AuthCookieFactory.REFRESH_COOKIE)
                .flatMap(token -> jwtService.parse(token, JwtService.TYPE_REFRESH))
                .or(() -> AuthCookieFactory.readCookie(request, AuthCookieFactory.ACCESS_COOKIE)
                        .flatMap(token -> jwtService.parse(token, JwtService.TYPE_ACCESS)))
                .map(jwtService::extractSessionId);
    }

    private Long requireUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new UnauthorizedException("Not authenticated");
        }
        return userId;
    }

    /**
     * Behind a reverse proxy this is the proxy's address unless
     * {@code server.forward-headers-strategy} is enabled — see the warning in
     * application.yaml. Getting it wrong only widens the per-IP budget to a
     * shared bucket; the per-email budget is unaffected either way.
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
