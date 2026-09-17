package com.civicpulse.backend_spring.service.ward;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.repository.WardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The centroid resolver — the FALLBACK since V13 imported real BBMP boundaries
 * and {@link PostGisWardResolver} became the default.
 *
 * <p>It is still tested because it is still selectable: a deployment without
 * PostGIS runs on it, and so does ward data that arrived with no geometry. What
 * it must keep is the property that matters — refusing an out-of-area point
 * rather than filing it into whichever ward happened to be least far away.
 *
 * <p>It queries ACTIVE wards only. Retired wards (the four V2 placeholders)
 * still carry centroids, and resolving a live report into one would quietly
 * undo the ward import.
 */
@ExtendWith(MockitoExtension.class)
class CentroidWardResolverTest {

    @Mock
    private WardRepository wardRepository;

    private CentroidWardResolver resolver;

    /**
     * The four wards V2/V3 seeded. V13 retired these in favour of the real
     * BBMP dataset; they are kept here as fixtures because this test is about
     * the ALGORITHM — nearest centroid within a radius — not about which wards
     * happen to exist.
     */
    private static final Ward WARD_1 = ward(1L, "1", "Ward 1", 13.0358, 77.5970);
    private static final Ward WARD_2 = ward(2L, "2", "Ward 2", 13.1007, 77.5963);
    private static final Ward WARD_17 = ward(3L, "17", "Ward 17", 12.9250, 77.5938);
    private static final Ward WARD_23 = ward(4L, "23", "Ward 23", 12.9784, 77.6408);

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.setWardResolutionMaxKm(25);
        resolver = new CentroidWardResolver(wardRepository, properties);
    }

    @Test
    @DisplayName("a point in Jayanagar resolves to the nearest ward centroid")
    void resolvesNearest() {
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(WARD_1, WARD_2, WARD_17, WARD_23));

        Optional<Ward> resolved = resolver.resolve(12.9260, 77.5940);

        assertThat(resolved).contains(WARD_17);
    }

    @Test
    @DisplayName("Indiranagar resolves to Ward 23, not the geographically similar Ward 17")
    void discriminatesBetweenNearbyWards() {
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(WARD_1, WARD_2, WARD_17, WARD_23));

        Optional<Ward> resolved = resolver.resolve(12.9790, 77.6400);

        assertThat(resolved).contains(WARD_23);
    }

    @Test
    @DisplayName("a point in another city resolves to nothing")
    void rejectsOutOfRange() {
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(WARD_1, WARD_2, WARD_17, WARD_23));

        // Mumbai, ~840 km away. Returning "the nearest Bengaluru ward" here
        // would corrupt the dashboard and Phase 2's ward+category pre-filter,
        // invisibly — which is exactly what the radius exists to prevent.
        Optional<Ward> resolved = resolver.resolve(19.0760, 72.8777);

        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("wards without a centroid are skipped rather than crashing")
    void skipsWardsWithoutCoordinates() {
        Ward noCentroid = ward(5L, "31", "Ward 31", null, null);
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(noCentroid, WARD_17));

        Optional<Ward> resolved = resolver.resolve(12.9260, 77.5940);

        assertThat(resolved).contains(WARD_17);
    }

    @Test
    @DisplayName("no usable ward at all resolves to nothing")
    void emptyWhenNoCandidates() {
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());

        assertThat(resolver.resolve(12.9716, 77.5946)).isEmpty();
    }

    @Test
    @DisplayName("retired wards are never candidates, even when they are the nearest")
    void ignoresRetiredWards() {
        // The repository method itself filters, so this asserts the resolver
        // asks the RIGHT question. Calling findAll() here would have quietly
        // resolved live reports into the placeholder wards V13 stood down —
        // which is precisely the mis-filing the ward import removed.
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(WARD_23));

        Optional<Ward> resolved = resolver.resolve(12.9260, 77.5940);

        assertThat(resolved).contains(WARD_23);
    }

    private static Ward ward(Long id, String code, String name, Double lat, Double lon) {
        return Ward.builder()
                .id(id)
                .code(code)
                .name(name)
                .latitude(lat)
                .longitude(lon)
                .build();
    }
}
