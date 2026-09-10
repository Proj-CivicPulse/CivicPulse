package com.civicpulse.backend_spring.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Non-secret application settings. Validated at startup so a
 * misconfiguration fails immediately instead of at the first request.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Validated
@Getter
@Setter
public class AppProperties {

    /**
     * Browser origins allowed by CORS. Bound from a comma-separated
     * FRONTEND_ORIGIN, so one variable covers the production frontend plus any
     * preview deployments.
     *
     * Each entry may contain a "*" wildcard and is applied via
     * {@code setAllowedOriginPatterns} — that is what makes Vercel's
     * per-deploy preview URLs (https://myapp-*.vercel.app) usable, since they
     * change on every commit and cannot be listed exhaustively.
     *
     * A BARE "*" is rejected at startup in SecurityConfig: credentials are
     * allowed here, and blanket-wildcard + credentials is exactly the
     * combination the CORS spec forbids.
     */
    @NotEmpty
    private List<String> frontendOrigin;

    /** Whether auth cookies carry the Secure flag. Must be true over HTTPS. */
    private boolean cookieSecure = false;

    /**
     * Hard ceiling on the /health database probe, in seconds. Default
     * accommodates a Neon cold start; lower it for a local database.
     */
    @Positive
    private long healthTimeoutSeconds = 10;

    /**
     * Which WardResolver implementation to use. "centroid" is nearest-seeded-
     * centroid; a future "postgis" would do point-in-polygon against real
     * boundaries. Selected by @ConditionalOnProperty, so no caller names an
     * implementation.
     */
    private String wardResolver = "centroid";

    /**
     * How far a point may be from a ward centroid and still resolve to it.
     *
     * Bengaluru municipal area is roughly a 15 km radius, so 25 km covers the
     * metro with slop while firmly rejecting a submission from another city.
     * Beyond this the resolver returns empty and the caller gets a 404 rather
     * than a silent, invisible mis-filing.
     */
    @Positive
    private double wardResolutionMaxKm = 25;

    /**
     * Shared secret for /internal/* (Node -> Spring). Blank means /internal/*
     * denies everything: fail closed, so a missing secret is a broken
     * integration rather than an open door.
     */
    private String internalToken = "";

    private final IncidentGrouping incidentGrouping = new IncidentGrouping();

    private final Geocoding geocoding = new Geocoding();

    private final Matching matching = new Matching();

    private final Auth auth = new Auth();

    private final Email email = new Email();

    /**
     * Transactional email, used only by the verification and password-reset
     * flows. Defaults to "log" so a fresh clone runs both end to end with no
     * account and no key — the same principle as {@code geocoding.provider=none}.
     */
    @Getter
    @Setter
    public static class Email {

        /** "resend" for real delivery, "log" to print the message instead. */
        private String provider = "log";

        /** Resend API key. Server-side only; never reaches the browser. */
        private String apiKey = "";

        /**
         * The From address. Must be on a domain verified with the provider —
         * an unverified sender is the usual cause of a 4xx from Resend.
         */
        private String fromAddress = "CivicPulse <onboarding@resend.dev>";

        /**
         * Base URL the emailed links point at — the FRONTEND, not this service,
         * since a human clicks them and lands on a page.
         *
         * Separate from frontendOrigin (which is a CORS allowlist entry) because
         * they are answers to different questions and can legitimately differ,
         * e.g. behind a path-prefixed proxy.
         */
        @NotBlank
        private String linkBaseUrl = "http://localhost:5173";

        /**
         * Verification link lifetime. Long, because the cost of expiry is a
         * confused user requesting another one, and the token grants only
         * "this address is real" — not access to anything.
         */
        @Positive
        private long verificationTtlHours = 24;

        /**
         * Reset link lifetime. Deliberately short: this token IS the account
         * until it is used, so the window in which a forwarded or intercepted
         * message is dangerous should be as small as usability allows.
         */
        @Positive
        private long resetTtlMinutes = 60;
    }

    /**
     * Login brute-force budgets and refresh-token rotation tolerance.
     * See LoginRateLimiter and RefreshTokenService for the reasoning behind
     * each default.
     */
    @Getter
    @Setter
    public static class Auth {

        /**
         * Failed sign-ins allowed against one email address before it is locked.
         *
         * Deliberately tight. A person who has genuinely forgotten their
         * password does not make five attempts and then a sixth that works —
         * they stop and reset it. An attacker needs thousands, so anything in
         * this range costs them everything and costs a real user nothing.
         */
        @Positive
        private int maxAttemptsPerEmail = 5;

        /**
         * Failed sign-ins allowed from one IP across ALL accounts, which is what
         * catches password spraying (one common password, many accounts — never
         * enough failures on any single account to trip the limit above).
         *
         * Much looser than the per-email budget because an office, a campus, or
         * a mobile carrier NAT puts a great many legitimate users behind one
         * address, and locking that out is a self-inflicted outage.
         */
        @Positive
        private int maxAttemptsPerIp = 50;

        /** Failures older than this stop counting toward either budget. */
        @Positive
        private long attemptWindowSeconds = 900;

        /** How long a key stays locked once its budget is exhausted. */
        @Positive
        private long lockoutSeconds = 900;

        /**
         * Ceiling on tracked login keys. Email keys are attacker-chosen strings,
         * so the table needs a bound or a flood of invented addresses becomes a
         * memory-exhaustion attack against the limiter itself.
         */
        @Positive
        private int maxTrackedKeys = 100_000;

        /**
         * How long an already-rotated refresh token is still accepted as a
         * concurrent-refresh race rather than a replayed theft.
         *
         * Two browser tabs holding the same cookie can refresh at the same
         * instant; without a small window one of them would trigger reuse
         * detection and sign the user out of everything for no reason.
         */
        @Positive
        private long refreshReuseGraceSeconds = 30;

        /**
         * Emails one address may trigger per window (verification resends,
         * password-reset requests).
         *
         * These endpoints send mail to an address the CALLER names, which makes
         * them an email-bombing tool pointed at someone else's inbox — and,
         * unthrottled, a way to burn the sending domain's reputation. Low,
         * because nobody legitimately needs a fourth reset link in an hour.
         */
        @Positive
        private int emailRequestsPerAddress = 3;

        /** The same budget per IP, which is what stops a walk through many addresses. */
        @Positive
        private int emailRequestsPerIp = 10;

        /** Window and lockout for both email budgets above. */
        @Positive
        private long emailRequestWindowSeconds = 3600;
    }

    /**
     * Reverse geocoding, used to attach a street address to coordinates.
     *
     * Off by default: it needs a paid third-party key, and the project must be
     * runnable by a contributor who has not got one.
     */
    @Getter
    @Setter
    public static class Geocoding {

        /** "google" or "none". */
        private String provider = "none";

        /**
         * Server-side only, never shipped to the browser. Restrict it by IP in
         * the Cloud Console — unlike a Maps JS key this one can stay secret.
         */
        private String apiKey = "";

        /**
         * Decimal places the cache key rounds coordinates to.
         *
         * 3 (~111 m) is the default because it is what the data actually
         * supports: measured against real seeded reports, 4 dp (~11 m) gave a
         * 3% cache hit rate — reports about one pothole simply are not placed
         * within 11 m of each other — while 3 dp gave ~49%.
         *
         * The risk of going coarser is two genuinely different streets sharing
         * an address near a junction. 3 dp is the balance point for a dense
         * Indian city; lower it to 4 for somewhere sparser where 111 m spans
         * several distinct addresses.
         */
        @Positive
        private int cacheKeyScale = 3;
    }

    /** Bound on outbound third-party calls, so a slow provider cannot pin a request thread. */
    @Positive
    private long httpConnectTimeoutMs = 3000;

    @Positive
    private long httpReadTimeoutMs = 5000;

    /**
     * Phase 2 semantic matching: the async trigger to backend-node, the circuit
     * breaker that protects the citizen path from a slow/dead Node, and the
     * reconcile sweep that closes the loop.
     */
    @Getter
    @Setter
    public static class Matching {

        /** backend-node's base URL. The trigger POSTs {@code /complaints/{id}/process} here. */
        @NotBlank
        private String nodeBaseUrl = "http://localhost:3001";

        /**
         * Read timeout on the trigger call. Deliberately short: the citizen has
         * already had their response, and a slow Node that has taken the
         * PROCESSING claim will finish and call back regardless — the worker
         * should not sit waiting on it.
         */
        @Positive
        private long processTimeoutMs = 2500;

        /**
         * A PROCESSING claim older than this is stale (Node crashed mid-run) and
         * the reconcile sweep will offer it again. MUST match backend-node's
         * PROCESSING_STALE_SECONDS — Node's compare-and-swap is the authority;
         * this value only pre-filters candidates, so drift merely costs a wasted
         * {@code skipped} round-trip. Keep it above Node's embedding timeout + slack.
         */
        @Positive
        private long processingStaleSeconds = 45;

        private final CircuitBreaker circuitBreaker = new CircuitBreaker();

        private final Reconciliation reconciliation = new Reconciliation();

        @Getter
        @Setter
        public static class CircuitBreaker {

            /** Consecutive connection-level failures before the breaker opens. */
            @Positive
            private int failureThreshold = 3;

            /** How long the breaker stays open before allowing a trial call. */
            @Positive
            private long openSeconds = 60;
        }

        @Getter
        @Setter
        public static class Reconciliation {

            /** Off switches the scheduled sweep entirely (the context test sets this false). */
            private boolean enabled = true;

            @Positive
            private long intervalMs = 60_000;

            /** Complaints re-processed per sweep. */
            @Positive
            private int batchSize = 25;
        }
    }

    /**
     * Bounds on the naive complaint-to-incident grouping. Since Phase 2 this is
     * the <em>fallback</em> matcher — it runs only when backend-node is
     * unreachable, and tags the complaint {@code degraded}.
     */
    @Getter
    @Setter
    public static class IncidentGrouping {

        /** "naive" (ward + category + window + radius) or "none". */
        private String strategy = "naive";

        /**
         * Only join an incident opened within this many days. Without it,
         * ward+category collapses all history into one incident per category
         * per ward, permanently.
         */
        @Positive
        private int windowDays = 14;

        /**
         * Only join an incident whose centroid is within this radius. Turns
         * "every pothole in Ward 17" into "potholes near one street", which is
         * a materially better baseline for near-zero cost.
         */
        @Positive
        private double maxDistanceKm = 1.5;
    }
}
