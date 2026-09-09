package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.JwtProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRevocationRegistryTest {

    private static SessionRevocationRegistry registry(long accessExpirationMs) {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-only-secret-value-not-used-anywhere-real-0123456789");
        props.setAccessExpiration(accessExpirationMs);
        props.setRefreshExpiration(604_800_000L);
        return new SessionRevocationRegistry(props);
    }

    @Test
    @DisplayName("an unknown session is not revoked")
    void unknownSessionIsLive() {
        assertThat(registry(900_000).isRevoked("session-a")).isFalse();
    }

    @Test
    @DisplayName("a revoked session is refused immediately")
    void revokedSessionIsBlocked() {
        SessionRevocationRegistry registry = registry(900_000);

        registry.revoke("session-a");

        assertThat(registry.isRevoked("session-a")).isTrue();
        assertThat(registry.isRevoked("session-b")).isFalse();
    }

    @Test
    @DisplayName("revokeAll blocks every session handed to it")
    void revokesInBulk() {
        SessionRevocationRegistry registry = registry(900_000);

        registry.revokeAll(List.of("session-a", "session-b"));

        assertThat(registry.isRevoked("session-a")).isTrue();
        assertThat(registry.isRevoked("session-b")).isTrue();
    }

    @Test
    @DisplayName("an entry stops blocking once its access tokens would have expired anyway")
    void entryExpiresWithTheAccessToken() throws InterruptedException {
        SessionRevocationRegistry registry = registry(100);

        registry.revoke("session-a");
        assertThat(registry.isRevoked("session-a")).isTrue();

        Thread.sleep(200);

        // Past this point the token is refused on its own exp claim, so
        // holding the entry any longer would only leak memory.
        assertThat(registry.isRevoked("session-a")).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("purgeExpired drops elapsed entries and keeps live ones")
    void purgeKeepsLiveEntries() throws InterruptedException {
        SessionRevocationRegistry shortLived = registry(100);
        shortLived.revoke("session-a");
        Thread.sleep(200);
        shortLived.revoke("session-b");   // still live

        assertThat(shortLived.purgeExpired()).isEqualTo(1);
        assertThat(shortLived.isRevoked("session-b")).isTrue();
    }

    @Test
    @DisplayName("a null or blank session id is ignored rather than stored")
    void ignoresMissingSessionId() {
        SessionRevocationRegistry registry = registry(900_000);

        registry.revoke(null);
        registry.revoke("  ");

        assertThat(registry.size()).isZero();
        assertThat(registry.isRevoked(null)).isFalse();
    }
}
