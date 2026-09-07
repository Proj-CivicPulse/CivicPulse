package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.repository.WardRepository;
import com.civicpulse.backend_spring.util.GeoDistance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Nearest seeded ward centroid, bounded by app.ward-resolution-max-km.
 *
 * This is a placeholder for real boundary data: with a handful of centroids
 * across a city it is coarse by construction, and the radius is what keeps it
 * from being absurd rather than what makes it accurate.
 */
@Service
@ConditionalOnProperty(name = "app.ward-resolver", havingValue = "centroid", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CentroidWardResolver implements WardResolver {

    private final WardRepository wardRepository;
    private final AppProperties appProperties;

    @Override
    @Transactional(readOnly = true)
    public Optional<Ward> resolve(double latitude, double longitude) {
        double maxKm = appProperties.getWardResolutionMaxKm();

        // A handful of rows, so findAll per call is cheaper than any cache
        // would be. If wards ever number in the thousands, a periodically
        // refreshed snapshot is the fix — not a per-request query.
        Optional<Ward> nearest = wardRepository.findAll().stream()
                // Wards without a centroid cannot participate. They are still
                // selectable by hand through GET /wards.
                .filter(ward -> ward.getLatitude() != null && ward.getLongitude() != null)
                .min((a, b) -> Double.compare(
                        distanceKm(latitude, longitude, a),
                        distanceKm(latitude, longitude, b)))
                .filter(ward -> distanceKm(latitude, longitude, ward) <= maxKm);

        if (nearest.isEmpty()) {
            log.debug("No ward within {} km of ({}, {})", maxKm, latitude, longitude);
        }
        return nearest;
    }

    private static double distanceKm(double latitude, double longitude, Ward ward) {
        return GeoDistance.kilometresBetween(
                latitude, longitude, ward.getLatitude(), ward.getLongitude());
    }
}
