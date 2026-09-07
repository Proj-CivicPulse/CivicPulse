package com.civicpulse.backend_spring.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

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

    /** The one browser origin allowed by CORS. Never a wildcard. */
    @NotBlank
    private String frontendOrigin;

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
     * Bounds on the placeholder complaint-to-incident grouping that runs until
     * Phase 2 semantic matching lands.
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
