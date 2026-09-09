package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.exception.TooManyAttemptsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Budgets for the two auth endpoints an outsider can hammer: sign-in, and the
 * ones that send mail.
 *
 * SIGN-IN. BCrypt makes each guess expensive to verify, which slows an attacker
 * who has stolen the hashes — it does nothing about someone guessing over HTTP,
 * who needs no hashes at all. This is the control for that: without it a
 * password can be attacked at whatever rate the network allows.
 *
 * Two independent sign-in budgets, because they stop different attacks:
 *   - per email — one account hammered with many passwords. Tight (a handful),
 *     since a real person does not fail five times and keep going.
 *   - per IP — many accounts sprayed with one common password, which never
 *     trips a per-account limit. Loose, because an office or a mobile carrier
 *     puts many innocent users behind one address.
 *
 * A successful sign-in clears the EMAIL budget only — crediting the IP too would
 * let an attacker reset it at will by signing into an account he owns.
 *
 * EMAIL SENDS (verification resend, password reset). Different shape: every
 * request counts, not just failed ones, because the abuse is the send itself.
 * These endpoints mail an address the caller names, so unthrottled they are an
 * email-bombing tool aimed at someone else's inbox and a fast way to burn the
 * sending domain's reputation.
 *
 * Keyed on a rejected email, a 429 says only that this address has been tried
 * recently — true whether or not it is registered — so it does not become the
 * account-enumeration oracle that {@code AuthService} and the reset flow avoid.
 *
 * ⚠️ SINGLE-INSTANCE ONLY, like {@link SessionRevocationRegistry}: per-JVM
 * counters mean N replicas allow N times the budget. Shared state (Redis) is the
 * fix if this is ever scaled out.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiter {

    /** Immutable so a bucket can be read outside the map's atomic section. */
    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private static final String LOGIN_EMAIL = "login:email:";
    private static final String LOGIN_IP = "login:ip:";
    private static final String MAIL_EMAIL = "mail:email:";
    private static final String MAIL_IP = "mail:ip:";

    private final Map<String, Attempts> buckets = new ConcurrentHashMap<>();

    private final AppProperties appProperties;

    // ---------------------------------------------------------------- sign-in

    /**
     * @throws TooManyAttemptsException if either sign-in budget is locked
     */
    public void checkLoginAllowed(String ip, String email) {
        requireUnlocked(LOGIN_EMAIL + normalize(email));
        requireUnlocked(LOGIN_IP + ip);
    }

    public void recordLoginFailure(String ip, String email) {
        AppProperties.Auth auth = appProperties.getAuth();
        long window = auth.getAttemptWindowSeconds();
        long lockout = auth.getLockoutSeconds();

        countAttempt(LOGIN_EMAIL + normalize(email), auth.getMaxAttemptsPerEmail(), window, lockout, true);
        countAttempt(LOGIN_IP + ip, auth.getMaxAttemptsPerIp(), window, lockout, false);
    }

    /** Clears the email budget. The IP budget deliberately survives — see the class note. */
    public void recordLoginSuccess(String ip, String email) {
        buckets.remove(LOGIN_EMAIL + normalize(email));
    }

    // ------------------------------------------------------------ email sends

    /**
     * Counts a request to send mail and refuses once the budget is spent.
     *
     * Check and record in one call, unlike sign-in: here the request itself is
     * the cost, so there is no "success" that should go uncounted.
     *
     * @throws TooManyAttemptsException if either email budget is locked
     */
    public void checkAndRecordEmailRequest(String ip, String email) {
        AppProperties.Auth auth = appProperties.getAuth();
        long window = auth.getEmailRequestWindowSeconds();

        String emailKey = MAIL_EMAIL + normalize(email);
        String ipKey = MAIL_IP + ip;

        requireUnlocked(emailKey);
        requireUnlocked(ipKey);

        countAttempt(emailKey, auth.getEmailRequestsPerAddress(), window, window, true);
        countAttempt(ipKey, auth.getEmailRequestsPerIp(), window, window, false);
    }

    // ---------------------------------------------------------------- internals

    private void requireUnlocked(String key) {
        Attempts current = buckets.get(key);
        if (current == null || current.lockedUntil() == null) {
            return;
        }
        Instant now = Instant.now();
        if (now.isBefore(current.lockedUntil())) {
            long retryAfter = Math.max(1, Duration.between(now, current.lockedUntil()).toSeconds());
            throw new TooManyAttemptsException(
                    "Too many attempts. Try again in " + describe(retryAfter) + ".", retryAfter);
        }
        // Lock elapsed — drop it so the next attempt starts a fresh window.
        buckets.remove(key, current);
    }

    private void countAttempt(String key, int maxAttempts, long windowSeconds,
                              long lockoutSeconds, boolean evictable) {
        Instant now = Instant.now();
        int maxTracked = appProperties.getAuth().getMaxTrackedKeys();

        // Email keys are attacker-chosen strings, so an unbounded map is a
        // memory-exhaustion vector. Expired entries are cleared first; if the
        // table is still full we stop tracking NEW email keys rather than grow.
        // IP keys are always tracked — the addresses in an attack are finite,
        // and they are what still catches a flood of invented addresses.
        if (evictable && !buckets.containsKey(key) && buckets.size() >= maxTracked) {
            purgeExpired();
            if (buckets.size() >= maxTracked) {
                log.warn("Auth attempt table is full ({} keys) — not tracking further addresses",
                        buckets.size());
                return;
            }
        }

        buckets.compute(key, (k, current) -> {
            // Fresh key, or the previous window has run out: start counting again.
            boolean startsNewWindow = current == null
                    || current.windowStart().isBefore(now.minusSeconds(windowSeconds));

            int attempts = startsNewWindow ? 1 : current.failures() + 1;
            Instant windowStart = startsNewWindow ? now : current.windowStart();
            Instant existingLock = startsNewWindow ? null : current.lockedUntil();

            // Evaluated on every path, including the first attempt of a window:
            // a budget of 1 must lock on that first attempt, and skipping the
            // check here would mean it never locked at all.
            Instant lockedUntil = attempts >= maxAttempts
                    ? now.plusSeconds(lockoutSeconds)
                    : existingLock;

            if (lockedUntil != null && existingLock == null) {
                log.warn("Locking {} after {} attempts", k, attempts);
            }
            return new Attempts(attempts, windowStart, lockedUntil);
        });
    }

    /** Called by the scheduled cleanup job. */
    public int purgeExpired() {
        AppProperties.Auth auth = appProperties.getAuth();
        // The longer of the two windows, so an email bucket is never dropped
        // early just because the shorter sign-in window has elapsed.
        long longestWindow = Math.max(auth.getAttemptWindowSeconds(), auth.getEmailRequestWindowSeconds());
        Instant staleBefore = Instant.now().minusSeconds(longestWindow);
        Instant now = Instant.now();

        int before = buckets.size();
        buckets.values().removeIf(a ->
                a.windowStart().isBefore(staleBefore)
                        && (a.lockedUntil() == null || now.isAfter(a.lockedUntil())));
        return before - buckets.size();
    }

    private static String describe(long seconds) {
        if (seconds < 60) {
            return seconds + " seconds";
        }
        long minutes = Math.max(1, seconds / 60);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String normalize(String email) {
        // Must match AuthService's normalisation, or "A@b.com" and "a@b.com"
        // would each get their own budget and double the allowance.
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** Visible for tests. */
    int size() {
        return buckets.size();
    }
}
