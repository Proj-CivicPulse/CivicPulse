package com.civicpulse.backend_spring.service.priority;

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
 * The calibration sheet against the real database, rolled back afterwards.
 * Scoped to a ward with no incidents of its own, with the clock fixed, so the
 * expected scores can be worked out by hand from the formula.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RankingSheetServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);

    @Autowired private RankingSheetService rankingSheetService;
    @Autowired private EntityManager entityManager;

    private long wardId;
    private String marker;

    @BeforeEach
    void setUp() {
        marker = "RANKTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = ((Number) entityManager.createNativeQuery("""
                SELECT id FROM wards
                WHERE active AND id NOT IN (SELECT ward_id FROM incidents)
                ORDER BY id LIMIT 1
                """).getSingleResult()).longValue();
    }

    private long incident(String status, String address, LocalDateTime createdAt) {
        String title = marker + " " + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents (ward_id, category, title, status, address, created_at, updated_at)
                VALUES (?1, 'pothole', ?2, ?3, ?4, ?5, ?5)
                """)
                .setParameter(1, wardId).setParameter(2, title).setParameter(3, status)
                .setParameter(4, address).setParameter(5, createdAt)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
    }

    private void report(long incidentId, LocalDateTime at) {
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, ?3, 'pothole', 12.9716, 77.5946, 'OPEN', ?4, 'MATCHED', ?5, ?5, ?5)
                """)
                .setParameter(1, wardId).setParameter(2, incidentId).setParameter(3, marker)
                .setParameter(4, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(5, at)
                .executeUpdate();
    }

    private long oldOne;
    private long busyOne;

    private void seed() {
        // 1 report, open 20 days: volume log(2)/log(26)=0.213, age saturated.
        // 10 * (0.4*0.213 + 0.2*1) = 2.85 → 2.9, low.
        oldOne = incident("OPEN", "12, Hosur Road, Bengaluru", NOW.minusDays(20));
        report(oldOne, NOW.minusDays(2));

        // 3 reports (2 in the last 24 h), open 2 days, all at one point:
        // 10 * (0.4*0.4255 + 0.3*0.4 + 0.2*(2/14)) = 3.19 → 3.2, medium.
        busyOne = incident("IN_PROGRESS", null, NOW.minusDays(2));
        report(busyOne, NOW.minusDays(2));
        report(busyOne, NOW.minusHours(5));
        report(busyOne, NOW.minusHours(1));

        long resolved = incident("RESOLVED", null, NOW.minusDays(3));
        report(resolved, NOW.minusDays(3));
    }

    private static String row(List<String> lines, long id) {
        return lines.stream().filter(l -> l.startsWith(id + ",")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the officer's sheet carries the formula's inputs and nothing derived from them")
    void sheet() {
        seed();

        List<String> lines = rankingSheetService.csv(List.of(wardId), false, NOW).lines().toList();

        assertThat(lines.getFirst()).isEqualTo(RankingSheetService.SHEET_HEADER + ",officer_rank");
        assertThat(lines.getFirst()).doesNotContain("score", "band", "reason");
        assertThat(lines).hasSize(3);                                  // header + 2 active; resolved left out
        assertThat(row(lines, oldOne)).endsWith(",\"12, Hosur Road, Bengaluru\",1,0,1,20,0,");
        assertThat(row(lines, busyOne)).endsWith(",,3,2,3,2,0,");
    }

    @Test
    @DisplayName("the analyst's key adds the score and band the formula gives those inputs")
    void key() {
        seed();

        List<String> lines = rankingSheetService.csv(List.of(wardId), true, NOW).lines().toList();

        assertThat(lines.getFirst()).isEqualTo(RankingSheetService.SHEET_HEADER + ",score,band");
        assertThat(row(lines, oldOne)).endsWith(",1,0,1,20,0,2.9,low");
        assertThat(row(lines, busyOne)).endsWith(",3,2,3,2,0,3.2,medium");
    }

    @Test
    @DisplayName("CSV fields are quoted per RFC 4180; the order scrambles ids but is stable")
    void formatting() {
        assertThat(RankingSheetService.field("a, b")).isEqualTo("\"a, b\"");
        assertThat(RankingSheetService.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(RankingSheetService.field(null)).isEmpty();
        assertThat(RankingSheetService.shuffleKey(7)).isEqualTo(RankingSheetService.shuffleKey(7));
        assertThat(RankingSheetService.shuffleKey(1) < RankingSheetService.shuffleKey(2))
                .isNotEqualTo(RankingSheetService.shuffleKey(2) < RankingSheetService.shuffleKey(3));
    }
}
