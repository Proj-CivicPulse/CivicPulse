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
 * Nearest ward centroid, bounded by app.ward-resolution-max-km.
 *
 * <p><b>No longer the default.</b> Since V13 imported real BBMP boundaries,
 * {@link PostGisWardResolver} does actual point-in-polygon and is what
 * {@code app.ward-resolver} selects when unset. This is kept, explicitly
 * selectable, for two cases: a deployment whose database has no PostGIS, and
 * ward data that arrived without geometry (the centroid columns stay populated
 * either way, derived by ST_PointOnSurface).
 *
 * <p>It is coarse by construction — nearest-centre is not containment, and the
 * two disagree near every boundary of an irregular ward. The radius is what
 * keeps it from being absurd, not what makes it accurate.
 */
@Service
@ConditionalOnProperty(name = "app.ward-resolver", havingValue = "centroid")
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
        // Active only: the retired V2 placeholders still carry centroids, and
        // resolving a live report into one would undo the V13 import.
        Optional<Ward> nearest = wardRepository.findByActiveTrueOrderByIdAsc().stream()
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
