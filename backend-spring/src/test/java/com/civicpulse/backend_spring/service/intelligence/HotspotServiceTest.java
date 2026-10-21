package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.dashboard.HotspotsDto;
import com.civicpulse.backend_spring.repository.HotspotRepository.Member;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Hotspots against the real database and PostGIS, rolled back afterwards.
 * Isolated by time like ExplorerQueriesTest: every row is reported in
 * January 2001 and every query is windowed to it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HotspotServiceTest {

    private static final String FROM = "2001-01-01T00:00:00Z";
    private static final String TO = "2001-02-01T00:00:00Z";

    /** ~0.0009° of latitude ≈ 100 m. */
    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;
    private static final double HUNDRED_M = 0.0009;

    @Autowired private HotspotService hotspotService;
    @Autowired private EntityManager entityManager;

    private String marker;
    private long wardId;
    private long otherWardId;

    @BeforeEach
    void setUp() {
        marker = "HOTSPOTTEST-" + UUID.randomUUID().toString().substring(0, 8);
        List<?> wards = entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 2").getResultList();
        wardId = ((Number) wards.get(0)).longValue();
        otherWardId = ((Number) wards.get(1)).longValue();
    }

    private long incident(String category, String status, double score, double lat, double lng, long ward) {
        String title = marker + " " + UUID.randomUUID();
        LocalDateTime reported = LocalDateTime.of(2001, 1, 15, 9, 0);
        entityManager.createNativeQuery("""
                INSERT INTO incidents
                    (ward_id, category, title, status, priority_score, complaint_count, latitude, longitude,
                     created_at, updated_at, first_reported_at, last_reported_at)
                VALUES (?1, ?2, ?3, ?4, ?5, 2, ?6, ?7, NOW(), NOW(), ?8, ?8)
                """)
                .setParameter(1, ward).setParameter(2, category).setParameter(3, title)
                .setParameter(4, status).setParameter(5, score)
                .setParameter(6, lat).setParameter(7, lng).setParameter(8, reported)
                .executeUpdate();
        long id = ((Number) entityManager.createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'OPEN', ?7, 'MATCHED', NOW(), NOW(), ?8)
                """)
                .setParameter(1, ward).setParameter(2, id).setParameter(3, marker + " member")
                .setParameter(4, category).setParameter(5, lat).setParameter(6, lng)
                .setParameter(7, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(8, reported)
                .executeUpdate();
        return id;
    }

    @Test
    @DisplayName("three active same-category incidents within 500 m form one hotspot; noise and closed ones do not")
    void clustersByCategoryAndDistance() {
        long a = incident("garbage", "OPEN", 6.0, LAT, LNG, wardId);
        long b = incident("garbage", "IN_PROGRESS", 2.0, LAT + HUNDRED_M, LNG, wardId);
        long c = incident("garbage", "OPEN", 4.0, LAT + 2 * HUNDRED_M, LNG, otherWardId);
        incident("garbage", "OPEN", 5.0, LAT + 20 * HUNDRED_M, LNG, wardId);          // 2 km away: noise
        incident("garbage", "RESOLVED", 9.0, LAT + HUNDRED_M / 2, LNG, wardId);       // not active
        incident("pothole", "OPEN", 5.0, LAT, LNG + HUNDRED_M / 100, wardId);          // only two potholes
        incident("pothole", "OPEN", 5.0, LAT, LNG + HUNDRED_M / 50, wardId);

        HotspotsDto result = hotspotService.hotspots(null, null, FROM, TO);

        assertThat(result.getDefinition().getMinIncidents()).isEqualTo(3);
        assertThat(result.getDefinition().getRadiusMetres()).isEqualTo(500);
        assertThat(result.getHotspots()).hasSize(1);

        HotspotsDto.Hotspot hotspot = result.getHotspots().getFirst();
        assertThat(hotspot.getCategory()).isEqualTo("garbage");
        assertThat(hotspot.getId()).isEqualTo("garbage-" + Math.min(a, Math.min(b, c)));
        assertThat(hotspot.getIncidents()).extracting(HotspotsDto.Member::getId)
                .containsExactly(String.valueOf(a), String.valueOf(c), String.valueOf(b));   // by priority
        assertThat(hotspot.getComplaintCount()).isEqualTo(6);
        assertThat(hotspot.getMaxPriorityScore()).isEqualTo(6.0);
        assertThat(hotspot.getMaxPriorityBand()).isEqualTo("high");
        assertThat(hotspot.getLatitude()).isCloseTo(LAT + HUNDRED_M, within(1e-6));
        assertThat(hotspot.getRadiusMetres()).isBetween(95.0, 105.0);
        assertThat(hotspot.getWards()).extracting(HotspotsDto.WardRef::getId)
                .containsExactly(String.valueOf(wardId), String.valueOf(otherWardId));
    }

    @Test
    @DisplayName("a ward filter keeps a straddling hotspot whole; category and window filters narrow it")
    void filters() {
        incident("water", "OPEN", 3.0, LAT, LNG, wardId);
        incident("water", "OPEN", 3.0, LAT + HUNDRED_M, LNG, wardId);
        incident("water", "OPEN", 3.0, LAT + 2 * HUNDRED_M, LNG, otherWardId);

        HotspotsDto byOtherWard = hotspotService.hotspots(null, List.of(otherWardId), FROM, TO);
        assertThat(byOtherWard.getHotspots()).hasSize(1);
        assertThat(byOtherWard.getHotspots().getFirst().getIncidentCount()).isEqualTo(3);

        assertThat(hotspotService.hotspots(List.of("pothole"), null, FROM, TO).getHotspots()).isEmpty();
        assertThat(hotspotService.hotspots(List.of("Water Supply"), null, FROM, TO).getHotspots()).hasSize(1);
        assertThat(hotspotService.hotspots(null, null, "2001-02-01T00:00:00Z", "2001-03-01T00:00:00Z")
                .getHotspots()).isEmpty();
    }

    @Test
    @DisplayName("group(): centre is the mean, radius the farthest member, and hotspots sort by priority")
    void groupsRows() {
        List<Member> rows = List.of(
                new Member("drainage", 0, 10, 1, "W1", 1, 2.0, 0.0, 0.0, null, null),
                new Member("drainage", 0, 11, 1, "W1", 1, 2.0, 0.0, 0.002, null, null),
                new Member("drainage", 0, 12, 1, "W1", 1, 2.0, 0.0, 0.004, null, null),
                new Member("garbage", 0, 20, 2, "W2", 3, 7.6, 1.0, 1.0, null, null),
                new Member("garbage", 0, 21, 2, "W2", 3, 1.0, 1.0, 1.001, null, null),
                new Member("garbage", 0, 22, 2, "W2", 3, 1.0, 1.0, 1.002, null, null));

        List<HotspotsDto.Hotspot> hotspots = HotspotService.group(rows);

        assertThat(hotspots).extracting(HotspotsDto.Hotspot::getId).containsExactly("garbage-20", "drainage-10");
        assertThat(hotspots.getFirst().getMaxPriorityBand()).isEqualTo("critical");
        HotspotsDto.Hotspot drainage = hotspots.get(1);
        assertThat(drainage.getLongitude()).isCloseTo(0.002, within(1e-9));
        // 0.002° of longitude at the equator ≈ 222 m.
        assertThat(drainage.getRadiusMetres()).isBetween(220.0, 224.0);
    }
}
