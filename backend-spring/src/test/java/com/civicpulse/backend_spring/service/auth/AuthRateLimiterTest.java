package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.exception.TooManyAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "citizen@example.com";

    private static AuthRateLimiter limiter(int perEmail, int perIp, long windowSeconds, long lockoutSeconds) {
        AppProperties props = new AppProperties();
        props.getAuth().setMaxAttemptsPerEmail(perEmail);
        props.getAuth().setMaxAttemptsPerIp(perIp);
        props.getAuth().setAttemptWindowSeconds(windowSeconds);
        props.getAuth().setLockoutSeconds(lockoutSeconds);
        return new AuthRateLimiter(props);
    }

    @Test
    @DisplayName("allows attempts up to the per-email budget, then locks")
    void locksEmailAtThreshold() {
        AuthRateLimiter limiter = limiter(3, 100, 900, 900);

        for (int i = 0; i < 2; i++) {
            assertThatCode(() -> limiter.checkLoginAllowed(IP, EMAIL)).doesNotThrowAnyException();
            limiter.recordLoginFailure(IP, EMAIL);
        }

        // Two failures — still under budget.
        assertThatCode(() -> limiter.checkLoginAllowed(IP, EMAIL)).doesNotThrowAnyException();

        limiter.recordLoginFailure(IP, EMAIL);

        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, EMAIL))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("Too many attempts");
    }

    @Test
    @DisplayName("locks an IP that sprays many different accounts")
    void locksIpAcrossDistinctEmails() {
        AuthRateLimiter limiter = limiter(5, 3, 900, 900);

        // One failure each against three accounts never trips the per-email
        // budget — this is exactly what password spraying looks like.
        limiter.recordLoginFailure(IP, "a@example.com");
        limiter.recordLoginFailure(IP, "b@example.com");
        limiter.recordLoginFailure(IP, "c@example.com");

        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, "d@example.com"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("a successful sign-in clears the email budget but not the IP budget")
    void successClearsEmailOnly() {
        AuthRateLimiter limiter = limiter(3, 4, 900, 900);

        limiter.recordLoginFailure(IP, EMAIL);
        limiter.recordLoginFailure(IP, EMAIL);   // email 2, ip 2
        limiter.recordLoginSuccess(IP, EMAIL);

        // Email counter was reset, so this is strike 1 and not strike 3.
        limiter.recordLoginFailure(IP, EMAIL);   // email 1, ip 3
        assertThatCode(() -> limiter.checkLoginAllowed(IP, EMAIL)).doesNotThrowAnyException();

        // The IP counter was NOT reset: this is its fourth failure and trips
        // its budget, proving a sign-in the attacker controls cannot be used
        // to buy back the IP allowance.
        limiter.recordLoginFailure(IP, EMAIL);   // email 2, ip 4
        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, EMAIL))
                .isInstanceOf(TooManyAttemptsException.class);

        // ...and it is genuinely the IP that locked, not the email: a
        // different address from a clean IP is still served.
        assertThatCode(() -> limiter.checkLoginAllowed("198.51.100.4", EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the lock lifts once the lockout period elapses")
    void lockExpires() throws InterruptedException {
        AuthRateLimiter limiter = limiter(1, 100, 900, 1);

        limiter.recordLoginFailure(IP, EMAIL);
        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, EMAIL))
                .isInstanceOf(TooManyAttemptsException.class);

        Thread.sleep(1_100);

        assertThatCode(() -> limiter.checkLoginAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failures older than the window stop counting")
    void windowExpires() throws InterruptedException {
        AuthRateLimiter limiter = limiter(2, 100, 1, 900);

        limiter.recordLoginFailure(IP, EMAIL);
        Thread.sleep(1_100);

        // The first failure has aged out, so this starts a fresh window
        // rather than being the second strike.
        limiter.recordLoginFailure(IP, EMAIL);
        assertThatCode(() -> limiter.checkLoginAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("email casing and padding share one budget")
    void normalizesEmail() {
        AuthRateLimiter limiter = limiter(2, 100, 900, 900);

        limiter.recordLoginFailure(IP, "  Citizen@Example.com ");
        limiter.recordLoginFailure(IP, "citizen@example.com");

        // Both spellings are the same account, so they must not each get a
        // full allowance.
        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, "CITIZEN@EXAMPLE.COM"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("reports how long to wait")
    void reportsRetryAfter() {
        AuthRateLimiter limiter = limiter(1, 100, 900, 120);

        limiter.recordLoginFailure(IP, EMAIL);

        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, EMAIL))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isBetween(100L, 120L));
    }

    @Test
    @DisplayName("stops tracking new email keys once the table is full, but keeps tracking IPs")
    void boundsMemory() {
        AppProperties props = new AppProperties();
        props.getAuth().setMaxAttemptsPerEmail(3);
        props.getAuth().setMaxAttemptsPerIp(3);
        props.getAuth().setAttemptWindowSeconds(900);
        props.getAuth().setLockoutSeconds(900);
        props.getAuth().setMaxTrackedKeys(4);
        AuthRateLimiter limiter = new AuthRateLimiter(props);

        // A flood of invented addresses from one host — the attack the cap exists for.
        for (int i = 0; i < 50; i++) {
            limiter.recordLoginFailure(IP, "victim" + i + "@example.com");
        }

        assertThat(limiter.size()).isLessThanOrEqualTo(4);

        // The IP was tracked throughout, so the flood is still caught.
        assertThatThrownBy(() -> limiter.checkLoginAllowed(IP, "anyone@example.com"))
                .isInstanceOf(TooManyAttemptsException.class);
    }
}
