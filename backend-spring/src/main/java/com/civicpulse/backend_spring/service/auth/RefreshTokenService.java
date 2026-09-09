package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.config.JwtProperties;
import com.civicpulse.backend_spring.entity.RefreshToken;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.RevocationReason;
import com.civicpulse.backend_spring.exception.UnauthorizedException;
import com.civicpulse.backend_spring.repository.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Owns the lifecycle of a login session: start it, rotate it, end it.
 *
 * Rotation with reuse detection is the point. Every /auth/refresh retires the
 * presented token and issues a successor, so a refresh token is single-use. If a
 * retired token turns up again, the legitimate client cannot be the one
 * presenting it — it has the successor — so the copy is assumed stolen and the
 * whole session is revoked. Without that check, rotation would only be
 * bookkeeping: an attacker with a copied token would simply rotate it himself
 * and keep going.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    /** A freshly issued access/refresh pair, ready to be written as cookies. */
    public record IssuedTokens(String accessToken, String refreshToken) {
    }

    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRevocationRegistry revocationRegistry;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final AppProperties appProperties;

    /** Login and registration: a brand-new session, unrelated to any before it. */
    @Transactional
    public IssuedTokens startSession(User user) {
        return issue(user, UUID.randomUUID().toString());
    }

    /**
     * Exchanges a valid refresh token for a new pair on the same session.
     *
     * @param claims already signature- and expiry-verified by {@link JwtService}
     * @throws UnauthorizedException if the token is unknown, retired, or revoked
     */
    @Transactional
    public IssuedTokens rotate(User user, Claims claims) {
        String jti = jwtService.extractTokenId(claims);
        String sessionId = jwtService.extractSessionId(claims);

        // Pre-session tokens (issued before V9) have neither claim. They are
        // cryptographically fine but cannot be tracked, so they are refused —
        // a one-off re-login at deploy time, noted in the migration.
        if (jti == null || sessionId == null) {
            throw new UnauthorizedException("Refresh token is missing or invalid");
        }

        RefreshToken stored = refreshTokenRepository.findByJti(jti)
                // Signed and unexpired but absent: the row was pruned, or the
                // database was reset under us. Nothing to rotate against.
                .orElseThrow(() -> new UnauthorizedException("Refresh token is missing or invalid"));

        LocalDateTime now = LocalDateTime.now();

        if (stored.isExpired(now)) {
            throw new UnauthorizedException("Refresh token is missing or invalid");
        }

        if (stored.isRevoked()) {
            if (isBenignRotationRace(stored, now)) {
                // Two tabs refreshed at nearly the same moment: both hold the
                // same cookie, one wins, the loser arrives here a beat later.
                // Punishing that would sign the user out for using two tabs.
                log.debug("Concurrent refresh on session {} — issuing on the same session", sessionId);
                return issue(user, sessionId);
            }

            // The real thing: a retired token replayed long after its
            // successor was issued. Assume the session is compromised.
            log.warn("Refresh token reuse detected for user {} on session {} — revoking the session",
                    user.getId(), sessionId);
            revokeSession(sessionId, RevocationReason.REUSE_DETECTED);
            throw new UnauthorizedException("Session is no longer valid");
        }

        stored.setRevokedAt(now);
        stored.setRevokedReason(RevocationReason.ROTATED);
        refreshTokenRepository.save(stored);

        return issue(user, sessionId);
    }

    /**
     * A rotated token re-presented within the grace window is treated as a
     * double-refresh race rather than an attack.
     *
     * The window is a deliberate trade: without it, ordinary multi-tab use
     * would randomly sign people out; with it, a stolen token has that many
     * seconds in which replay looks legitimate. Seconds, against a 7-day token
     * — the exposure is negligible next to the false-positive rate it prevents.
     * Only ROTATED qualifies: a token retired by an actual sign-out must never
     * be honoured, however recently.
     */
    private boolean isBenignRotationRace(RefreshToken stored, LocalDateTime now) {
        return stored.getRevokedReason() == RevocationReason.ROTATED
                && stored.getRevokedAt()
                .isAfter(now.minusSeconds(appProperties.getAuth().getRefreshReuseGraceSeconds()));
    }

    /** Ends one session — the current sign-out. */
    @Transactional
    public void revokeSession(String sessionId, RevocationReason reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeSession(sessionId, LocalDateTime.now(), reason);
        // Also stops the access token already in the browser, which the
        // database row alone would not.
        revocationRegistry.revoke(sessionId);
    }

    /** Ends every session for a user — "sign out on all devices". */
    @Transactional
    public int revokeAllForUser(Long userId, RevocationReason reason) {
        // Collected before the update, which sets revoked_at and so empties
        // this query's result.
        List<String> sessionIds = refreshTokenRepository.findActiveSessionIds(userId);
        int revoked = refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now(), reason);
        revocationRegistry.revokeAll(sessionIds);
        return revoked;
    }

    private IssuedTokens issue(User user, String sessionId) {
        String role = user.getRole().name();
        String jti = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        refreshTokenRepository.save(RefreshToken.builder()
                .jti(jti)
                .sessionId(sessionId)
                .userId(user.getId())
                .issuedAt(now)
                // Mirrors the JWT's own exp, so the row and the token agree.
                .expiresAt(now.plus(Duration.ofMillis(jwtProperties.getRefreshExpiration())))
                .build());

        return new IssuedTokens(
                jwtService.generateAccessToken(user.getId(), role, sessionId),
                jwtService.generateRefreshToken(user.getId(), role, sessionId, jti));
    }
}
