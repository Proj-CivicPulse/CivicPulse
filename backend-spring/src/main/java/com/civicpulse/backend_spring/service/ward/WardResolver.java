package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.entity.Ward;

import java.util.Optional;

/**
 * Derives a ward from a coordinate pair.
 *
 * Deliberately one method returning Optional and nothing else. A future
 * PostGisWardResolver doing real point-in-polygon against imported boundary
 * geometry satisfies this identically, so swapping the implementation is a
 * configuration change and touches no controller or service.
 *
 * Empty means "no ward covers that point" — a real answer, not a failure.
 * Callers render it as 404 rather than guessing a ward, because silently
 * filing an out-of-city report into the least-distant ward corrupts both the
 * dashboard and Phase 2's ward+category candidate pre-filter, invisibly.
 */
public interface WardResolver {

    Optional<Ward> resolve(double latitude, double longitude);
}
