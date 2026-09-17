package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.repository.WardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Real point-in-polygon against imported ward boundaries. The default resolver
 * since V13 imported the 243 BBMP wards.
 *
 * <p>This is the implementation {@link WardResolver}'s javadoc anticipated. It
 * answers the question the centroid resolver could only approximate: not "which
 * ward centre is nearest" but "which ward actually contains this point". With
 * irregular, elongated or horseshoe-shaped wards — which real municipal wards
 * routinely are — those two answers differ near every boundary, and the
 * difference is invisible: a mis-assigned complaint is silently unable to group
 * with the reports it belongs with, because ward is a hard constraint on both
 * matchers.
 *
 * <p>PostGIS does the work through a GiST index on {@code wards.boundary}, so
 * this is an index lookup, not a scan of 243 polygons totalling ~73k vertices.
 *
 * <p><b>It does not fall back to nearest-centroid.</b> A point outside every
 * boundary returns empty, exactly as the interface specifies, and the caller
 * renders that as a 404. Quietly substituting the least-distant ward would
 * reintroduce the silent mis-filing this class exists to remove — and it would
 * do so precisely in the cases where the answer is least trustworthy.
 */
@Service
@ConditionalOnProperty(name = "app.ward-resolver", havingValue = "postgis", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PostGisWardResolver implements WardResolver {

    private final WardRepository wardRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Ward> resolve(double latitude, double longitude) {
        Optional<Ward> containing = wardRepository.findContaining(latitude, longitude);

        if (containing.isEmpty()) {
            log.debug("No ward boundary contains ({}, {})", latitude, longitude);
        }
        return containing;
    }
}
