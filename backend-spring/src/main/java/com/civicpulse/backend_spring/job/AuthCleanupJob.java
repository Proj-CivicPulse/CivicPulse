package com.civicpulse.backend_spring.job;

import com.civicpulse.backend_spring.repository.AuthTokenRepository;
import com.civicpulse.backend_spring.repository.RefreshTokenRepository;
import com.civicpulse.backend_spring.service.auth.AuthRateLimiter;
import com.civicpulse.backend_spring.service.auth.SessionRevocationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Housekeeping for the three stores the auth layer accumulates state in.
 *
 * None of this is required for correctness — every one of them already ignores
 * stale entries when read. It exists so the table and the two maps do not grow
 * without bound over a long uptime: refresh_tokens gains a row per sign-in and
 * per rotation (with a 15-minute access token, that is ~96 rows per user per
 * day of active use), which would otherwise never be reclaimed.
 *
 * Hourly rather than on a timer of its own: expired rows are harmless, so this
 * is a slow background tidy, not something with a deadline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthCleanupJob {

    private static final long ONE_HOUR_MS = 3_600_000L;

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenRepository authTokenRepository;
    private final SessionRevocationRegistry revocationRegistry;
    private final AuthRateLimiter rateLimiter;

    @Scheduled(fixedDelay = ONE_HOUR_MS, initialDelay = ONE_HOUR_MS)
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();

        // Past expiry the token is refused on its own exp claim before this
        // table is consulted, so the row is already meaningless.
        int deletedRefresh = refreshTokenRepository.deleteExpired(now);
        // Same for verification and reset links: expiry is checked on the row
        // itself, so a lapsed one grants nothing before it is deleted.
        int deletedAuthTokens = authTokenRepository.deleteExpired(now);
        int purgedSessions = revocationRegistry.purgeExpired();
        int purgedAttempts = rateLimiter.purgeExpired();

        if (deletedRefresh > 0 || deletedAuthTokens > 0 || purgedSessions > 0 || purgedAttempts > 0) {
            log.info("Auth cleanup: {} expired refresh tokens, {} expired email tokens, "
                            + "{} revoked sessions, {} rate-limit records",
                    deletedRefresh, deletedAuthTokens, purgedSessions, purgedAttempts);
        }
    }
}
