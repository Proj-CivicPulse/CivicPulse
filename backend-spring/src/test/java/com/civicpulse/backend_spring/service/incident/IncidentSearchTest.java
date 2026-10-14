package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
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
 * GET /incidents filtering, paging and sorting, executed against the real
 * database (docs/api-contract.md, E1).
 *
 * <p>The filters are Criteria code — an EXISTS subquery, a COALESCE over the
 * score, an escaped LIKE — and the only proof that Hibernate turns them into
 * the SQL intended is running it. Rows are tagged with a per-test marker and
 * every search includes it as {@code q}, so the incidents already in the
 * database never enter an assertion. Spring rolls the transaction back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentSearchTest {

    @Autowired private IncidentService incidentService;
    @Autowired private EntityManager entityManager;

    private String marker;
    private Object wardId;

    @BeforeEach
    void setUp() {
        marker = "SEARCHTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult();
    }

    private Long incident(String title, double score, String status,
                          LocalDateTime firstReported, LocalDateTime lastReported) {
        entityManager.createNativeQuery("""
                INSERT INTO incidents
                    (ward_id, category, title, status, priority_score, complaint_count,
                     created_at, updated_at, first_reported_at, last_reported_at)
                VALUES (?1, 'pothole', ?2, ?3, ?4, 1, NOW(), NOW(), ?5, ?6)
                """)
                .setParameter(1, wardId)
                .setParameter(2, marker + " " + title)
                .setParameter(3, status)
                .setParameter(4, score)
                .setParameter(5, firstReported)
                .setParameter(6, lastReported)
                .executeUpdate();
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, marker + " " + title)
                .getSingleResult()).longValue();
    }

    private void member(Long incidentId, LocalDateTime reportedAt) {
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, 'search test member', 'pothole', 12.9716, 77.5946, 'OPEN',
                        ?3, 'MATCHED', NOW(), NOW(), ?4)
                """)
                .setParameter(1, wardId)
                .setParameter(2, incidentId)
                .setParameter(3, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(4, reportedAt)
                .executeUpdate();
    }

    private PageResponse<IncidentDto> search(List<String> bands, List<String> statuses,
                                             String from, String to, String q,
                                             String sort, Integer limit, Integer offset) {
        return incidentService.search(IncidentQuery.parse(
                null, null, statuses, bands, null, from, to,
                q == null ? marker : q, sort, null, limit, offset));
    }

    private static List<String> titles(PageResponse<IncidentDto> page) {
        return page.getItems().stream().map(i -> i.getTitle().substring(i.getTitle().indexOf(' ') + 1)).toList();
    }

    @Test
    @DisplayName("priorityBand selects exactly the rows carrying that band, boundaries included")
    void bandFilterMatchesBandLabel() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0);
        incident("low", 2.99, "OPEN", t, t);
        incident("high-edge", 5.5, "OPEN", t, t);
        incident("critical-edge", 7.5, "OPEN", t, t);

        PageResponse<IncidentDto> page = search(List.of("high", "critical"), null, null, null, null, null, null, null);

        assertThat(titles(page)).containsExactly("critical-edge", "high-edge");
        assertThat(page.getItems()).extracting(IncidentDto::getPriorityBand)
                .containsExactly("critical", "high");
        assertThat(page.getTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("from/to selects incidents with a member REPORTED in [from, to)")
    void windowIsOnMemberReportTime() {
        Long january = incident("january", 3.0, "OPEN",
                LocalDateTime.of(2026, 1, 10, 0, 0), LocalDateTime.of(2026, 1, 10, 0, 0));
        member(january, LocalDateTime.of(2026, 1, 10, 0, 0));

        Long march = incident("march", 3.0, "OPEN",
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 0, 0));
        member(march, LocalDateTime.of(2026, 3, 1, 0, 0));   // exactly on `from`: included

        Long april = incident("april", 3.0, "OPEN",
                LocalDateTime.of(2026, 4, 1, 0, 0), LocalDateTime.of(2026, 4, 1, 0, 0));
        member(april, LocalDateTime.of(2026, 4, 1, 0, 0));   // exactly on `to`: excluded

        PageResponse<IncidentDto> page = search(null, null,
                "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z", null, null, null, null);

        assertThat(titles(page)).containsExactly("march");
    }

    @Test
    @DisplayName("limit/offset page exactly, total counts every match, ties break stably")
    void pagesByOffset() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0);
        incident("a", 9.0, "OPEN", t, t);
        incident("b", 6.0, "OPEN", t, t);
        incident("c", 4.0, "OPEN", t, t);

        PageResponse<IncidentDto> page = search(null, null, null, null, null, null, 2, 1);

        assertThat(titles(page)).containsExactly("b", "c");
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getLimit()).isEqualTo(2);
        assertThat(page.getOffset()).isEqualTo(1);
    }

    @Test
    @DisplayName("status is repeatable and ANDs with the other filters")
    void statusFilterIsRepeatable() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0);
        incident("open", 6.0, "OPEN", t, t);
        incident("progress", 6.0, "IN_PROGRESS", t, t);
        incident("closed", 6.0, "CLOSED", t, t);

        PageResponse<IncidentDto> page = search(null, List.of("open", "in_progress"),
                null, null, null, "complaintCount", null, null);

        assertThat(titles(page)).containsExactlyInAnyOrder("open", "progress");
    }

    @Test
    @DisplayName("q treats % and _ literally")
    void textSearchEscapesWildcards() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0);
        incident("100% blocked", 3.0, "OPEN", t, t);
        incident("1000 blocked", 3.0, "OPEN", t, t);

        PageResponse<IncidentDto> page = search(null, null, null, null,
                marker + " 100%", null, null, null);

        assertThat(titles(page)).containsExactly("100% blocked");
    }

    @Test
    @DisplayName("sort=lastReportedAt orders by the report span, not by creation")
    void sortsByLastReported() {
        incident("older", 9.0, "OPEN",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0));
        incident("newer", 1.0, "OPEN",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0));

        PageResponse<IncidentDto> page = search(null, null, null, null, null, "lastReportedAt", null, null);

        assertThat(titles(page)).containsExactly("newer", "older");
        assertThat(page.getItems().get(0).getLastReportedAt()).isEqualTo("2026-08-01T00:00:00Z");
    }
}
