package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocklist of revoked sessions, consulted by {@link
 * com.civicpulse.backend_spring.config.JwtAuthenticationFilter} on every
 * authenticated request.
 *
 * WHY THIS EXISTS: revoking the refresh token stops a session being *renewed*,
 * but the access token already in the browser stays valid until it expires. So
 * without this, "sign out everywhere" would leave a stolen access token working
 * for up to another 15 minutes — exactly the window that matters after a
 * compromise is noticed.
 *
 * WHY IN MEMORY: the alternative is a database read on every single request,
 * which is a real cost against a Neon instance that suspends when idle (see the
 * Hikari settings in application.yaml). Entries are needed only until the access
 * tokens they cover would have expired anyway, so each one lives for exactly one
 * access-token lifetime and the map stays bounded by "sessions revoked in the
 * last 15 minutes" — a handful of entries, not a cache.
 *
 * ⚠️ SINGLE-INSTANCE ONLY. This is per-JVM state: run two replicas and a sign-out
 * handled by one is not seen by the other, whose access tokens stay live for the
 * remainder of their 15 minutes. The refresh side is still enforced everywhere
 * (it is in Postgres), so the session cannot be renewed on any instance — the
 * gap is bounded, not open-ended. Moving to more than one instance means backing
 * this with Redis, or accepting that bound deliberately.
 */
@Component
@RequiredArgsConstructor
public class SessionRevocationRegistry {

    /** session id -> the instant after which no access token for it could still be valid. */
    private final Map<String, Instant> revokedUntil = new ConcurrentHashMap<>();

    private final JwtProperties jwtProperties;

    public void revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // One access-token lifetime of slack is all that is needed: past that,
        // any access token bearing this session is refused on expiry alone.
        revokedUntil.put(sessionId, Instant.now().plusMillis(jwtProperties.getAccessExpiration()));
    }

    public void revokeAll(Collection<String> sessionIds) {
        sessionIds.forEach(this::revoke);
    }

    public boolean isRevoked(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Instant until = revokedUntil.get(sessionId);
        if (until == null) {
            return false;
        }
        if (Instant.now().isAfter(until)) {
            // Opportunistic cleanup so a session that is never asked about
            // again does not wait for the sweep.
            revokedUntil.remove(sessionId, until);
            return false;
        }
        return true;
    }

    /** Called by the scheduled cleanup job; entries are useless once elapsed. */
    public int purgeExpired() {
        Instant now = Instant.now();
        int before = revokedUntil.size();
        revokedUntil.values().removeIf(now::isAfter);
        return before - revokedUntil.size();
    }

    /** Visible for tests. */
    int size() {
        return revokedUntil.size();
    }
}
