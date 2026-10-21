package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.dashboard.AttentionDto;
import com.civicpulse.backend_spring.repository.AttentionRepository.CategoryVolume;
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

/**
 * "Needs attention" against the real database, rolled back afterwards. The
 * clock is fixed at 1 Feb 2001; every signal is evaluated as of that instant,
 * so real (2026) rows are in its future and never counted.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AttentionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2001, 2, 1, 0, 0);
    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;
    private static final double HUNDRED_M = 0.0009;

    @Autowired private AttentionService attentionService;
    @Autowired private EntityManager entityManager;

    private String marker;
    private long wardId;

    @BeforeEach
    void setUp() {
        marker = "ATTENTIONTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
    }

    private long incident(String category, String status, double score, double lat) {
        String title = marker + " " + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents
                    (ward_id, category, title, status, priority_score, complaint_count, latitude, longitude,
                     created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, ?5, 1, ?6, ?7, NOW(), NOW())
                """)
                .setParameter(1, wardId).setParameter(2, category).setParameter(3, title)
                .setParameter(4, status).setParameter(5, score).setParameter(6, lat).setParameter(7, LNG)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
    }

    private void report(long incidentId, String category, LocalDateTime at) {
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'OPEN', ?7, 'MATCHED', NOW(), NOW(), ?8)
                """)
                .setParameter(1, wardId).setParameter(2, incidentId).setParameter(3, marker + " report")
                .setParameter(4, category).setParameter(5, LAT).setParameter(6, LNG)
                .setParameter(7, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(8, at)
                .executeUpdate();
    }

    private static LocalDateTime jan(int day, int hour) {
        return LocalDateTime.of(2001, 1, day, hour, 0);
    }

    private long highId;
    private long worseningId;
    private long spikeId;
    private List<Long> hotspotIds;

    private void seed() {
        highId = incident("garbage", "OPEN", 6.0, LAT + 30 * HUNDRED_M);
        report(highId, "garbage", jan(10, 9));

        worseningId = incident("garbage", "OPEN", 2.0, LAT + 50 * HUNDRED_M);
        report(worseningId, "garbage", jan(30, 10));        // within 72 h of NOW
        report(worseningId, "garbage", jan(31, 8));

        // Pothole: 6 reports this week against 2 in the four weeks before → spike.
        spikeId = incident("pothole", "OPEN", 1.0, LAT + 70 * HUNDRED_M);
        for (int day = 26; day <= 28; day++) {                // before the 72 h window
            report(spikeId, "pothole", jan(day, 9));
            report(spikeId, "pothole", jan(day, 15));
        }
        long oldPothole = incident("pothole", "RESOLVED", 1.0, LAT + 90 * HUNDRED_M);
        report(oldPothole, "pothole", jan(5, 9));
        report(oldPothole, "pothole", jan(12, 9));

        // Three water incidents 100 m apart → a hotspot.
        long w1 = incident("water", "OPEN", 3.0, LAT);
        long w2 = incident("water", "IN_PROGRESS", 3.0, LAT + HUNDRED_M);
        long w3 = incident("water", "OPEN", 3.0, LAT + 2 * HUNDRED_M);
        for (long id : List.of(w1, w2, w3)) report(id, "water", jan(15, 9));
        hotspotIds = List.of(w1, w2, w3);

        long resolvedHigh = incident("garbage", "RESOLVED", 9.0, LAT + 110 * HUNDRED_M);
        report(resolvedHigh, "garbage", jan(31, 9));
    }

    private static List<String> ids(AttentionDto dto, String key) {
        return dto.getSignals().stream().filter(s -> s.getKey().equals(key)).findFirst().orElseThrow()
                .getIncidents().stream().map(AttentionDto.Item::getId).toList();
    }

    @Test
    @DisplayName("each signal selects exactly the incidents its rule describes, in a fixed order")
    void signals() {
        seed();

        AttentionDto dto = attentionService.attention(null, null, NOW);

        assertThat(dto.getAsOf()).isEqualTo("2001-02-01T00:00:00Z");
        assertThat(dto.getSignals()).extracting(AttentionDto.Signal::getKey)
                .containsExactly("high_priority", "worsening", "in_hotspot", "category_spike");
        assertThat(ids(dto, "high_priority")).containsExactly(String.valueOf(highId));
        assertThat(ids(dto, "worsening")).containsExactly(String.valueOf(worseningId));
        assertThat(ids(dto, "in_hotspot"))
                .containsExactlyInAnyOrderElementsOf(hotspotIds.stream().map(String::valueOf).toList());
        assertThat(ids(dto, "category_spike")).containsExactly(String.valueOf(spikeId));

        AttentionDto.Signal spike = dto.getSignals().get(3);
        assertThat(spike.getCategories()).singleElement().satisfies(s -> {
            assertThat(s.getCategory()).isEqualTo("pothole");
            assertThat(s.getLastWeek()).isEqualTo(6);
            assertThat(s.getPriorWeeklyMean()).isEqualTo(0.5);
        });

        AttentionDto.Item worsening = dto.getSignals().get(1).getIncidents().getFirst();
        assertThat(worsening.getRecentReports()).isEqualTo(2);
        assertThat(worsening.getLastReportedAt()).isEqualTo("2001-01-31T08:00:00Z");
    }

    @Test
    @DisplayName("a category filter narrows every signal; empty signals are still returned")
    void filtered() {
        seed();

        AttentionDto dto = attentionService.attention(List.of(wardId), List.of("Solid Waste"), NOW);

        assertThat(dto.getSignals()).hasSize(4);
        assertThat(ids(dto, "high_priority")).containsExactly(String.valueOf(highId));
        assertThat(ids(dto, "worsening")).containsExactly(String.valueOf(worseningId));
        assertThat(ids(dto, "in_hotspot")).isEmpty();
        assertThat(ids(dto, "category_spike")).isEmpty();
    }

    @Test
    @DisplayName("spike rule: ≥ 5 this week AND ≥ 2× the prior weekly mean")
    void spikeRule() {
        assertThat(AttentionService.isSpike(new CategoryVolume("x", 5, 0))).isTrue();
        assertThat(AttentionService.isSpike(new CategoryVolume("x", 4, 0))).isFalse();     // too few
        assertThat(AttentionService.isSpike(new CategoryVolume("x", 10, 20))).isTrue();    // mean 5, 2× = 10
        assertThat(AttentionService.isSpike(new CategoryVolume("x", 9, 20))).isFalse();    // under 2×
    }
}
