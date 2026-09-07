package com.civicpulse.backend_spring.service.geocoding;

import java.util.Optional;

/**
 * Turns a coordinate pair into a human-readable address.
 *
 * One method, returning Optional, so a provider swap is a configuration change.
 * Empty means "no address for that point" — a real answer, not a failure. A
 * provider outage also surfaces as empty rather than an exception, because
 * NOTHING in this application should fail because a third party is down: an
 * address is an enrichment, and a report without one is still a valid report.
 */
public interface GeocodingService {

    Optional<String> reverseGeocode(double latitude, double longitude);
}
