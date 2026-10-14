package com.civicpulse.backend_spring.service.explorer;

import com.civicpulse.backend_spring.dto.PageResponse;
import com.civicpulse.backend_spring.dto.complaint.ComplaintDto;
import com.civicpulse.backend_spring.dto.dashboard.CategoryExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.TrendDto;
import com.civicpulse.backend_spring.dto.dashboard.WardDashboardSummaryDto;
import com.civicpulse.backend_spring.dto.dashboard.WardExplorerSummaryDto;
import com.civicpulse.backend_spring.dto.incident.IncidentDto;
import com.civicpulse.backend_spring.service.complaint.ComplaintService;
import com.civicpulse.backend_spring.service.dashboard.DashboardService;
import com.civicpulse.backend_spring.service.incident.IncidentQuery;
import com.civicpulse.backend_spring.service.incident.IncidentService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * The Explorer aggregates (N1–N4, E2, E4, E6), executed against the real
 * database inside a transaction Spring rolls back.
 *
 * <p>Isolation is by TIME rather than by deleting anything: every row this
 * test writes is reported in January 2001 and every query is windowed to
 * January 2001. Production data begins in 2026, so the aggregates see exactly
 * these rows — while still running against the real schema, the real registry
 * and the real ward geometry.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExplorerQueriesTest {

    private static final String FROM = "2001-01-01T00:00:00Z";
    private static final String TO = "2001-02-01T00:00:00Z";

    @Autowired private ExplorerService explorerService;
    @Autowired private DashboardService dashboardService;
    @Autowired private IncidentService incidentService;
    @Autowired private ComplaintService complaintService;
    @Autowired private EntityManager entityManager;

    private String marker;
    private long wardId;

    @BeforeEach
    void setUp() {
        marker = "EXPLORERTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
    }

    private static LocalDateTime jan(int day, int hour) {
        return LocalDateTime.of(2001, 1, day, hour, 0);
    }

    private Long incident(String category, String status, double score,
                          LocalDateTime firstReported, LocalDateTime resolvedAt) {
        String title = marker + " " + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents
                    (ward_id, category, title, status, priority_score, complaint_count,
                     created_at, updated_at, first_reported_at, last_reported_at, resolved_at)
                VALUES (?1, ?2, ?3, ?4, ?5, 1, NOW(), NOW(), ?6, ?6, ?7)
                """)
                .setParameter(1, wardId)
                .setParameter(2, category)
                .setParameter(3, title)
                .setParameter(4, status)
                .setParameter(5, score)
                .setParameter(6, firstReported)
                .setParameter(7, resolvedAt)
                .executeUpdate();
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
    }

    private void complaint(Long incidentId, String category, LocalDateTime reportedAt) {
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, ?3, ?4, 12.9716, 77.5946, 'OPEN',
                        ?5, 'MATCHED', NOW(), NOW(), ?6)
                """)
                .setParameter(1, wardId)
                .setParameter(2, incidentId)
                .setParameter(3, marker + " member")
                .setParameter(4, category)
                .setParameter(5, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(6, reportedAt)
                .executeUpdate();
    }

    /** Two garbage complaints and one pothole; one high, one low, one resolved incident. */
    private void seed() {
        Long high = incident("garbage", "OPEN", 6.0, jan(10, 9), null);
        complaint(high, "garbage", jan(10, 9));
        Long low = incident("garbage", "IN_PROGRESS", 1.0, jan(11, 9), null);
        complaint(low, "garbage", jan(11, 9));
        Long resolved = incident("pothole", "RESOLVED", 4.0, jan(10, 0), jan(12, 0));   // 48 h
        complaint(resolved, "pothole", jan(10, 0));
    }

    @Test
    @DisplayName("N1: every active ward is a row; counts, dominant category and last activity are right")
    void wardSummaries() {
        seed();

        List<WardExplorerSummaryDto> rows = explorerService.wardSummaries(null, FROM, TO);

        long activeWards = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM wards WHERE active").getSingleResult()).longValue();
        assertThat(rows).hasSize((int) activeWards);

        WardExplorerSummaryDto ward = rows.stream()
                .filter(r -> r.getWardId().equals(String.valueOf(wardId))).findFirst().orElseThrow();
        assertThat(ward.getComplaintCount()).isEqualTo(3);
        assertThat(ward.getActiveIncidentCount()).isEqualTo(2);
        assertThat(ward.getHighPriorityIncidentCount()).isEqualTo(1);
        assertThat(ward.getDominantCategory()).isEqualTo("garbage");
        assertThat(ward.getLastActivityAt()).isEqualTo("2001-01-11T09:00:00Z");

        // Every other ward is present with zeros and nulls, not absent.
        assertThat(rows).filteredOn(r -> !r.getWardId().equals(String.valueOf(wardId)))
                .allSatisfy(r -> {
                    assertThat(r.getComplaintCount()).isZero();
                    assertThat(r.getDominantCategory()).isNull();
                    assertThat(r.getLastActivityAt()).isNull();
                });
    }

    @Test
    @DisplayName("N1: a category filter narrows complaints and incidents alike")
    void wardSummariesByCategory() {
        seed();

        WardExplorerSummaryDto ward = explorerService.wardSummaries(List.of("Solid Waste"), FROM, TO).stream()
                .filter(r -> r.getWardId().equals(String.valueOf(wardId))).findFirst().orElseThrow();

        // "Solid Waste" is an alias: canonicalised to garbage before querying.
        assertThat(ward.getComplaintCount()).isEqualTo(2);
        assertThat(ward.getActiveIncidentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("N2: per-category counts, RESOLVED-only resolution figures, default department")
    void categorySummaries() {
        seed();
        // A second resolution of 12 h makes the median (48 + 12) / 2 = 30 h.
        Long quick = incident("pothole", "RESOLVED", 4.0, jan(15, 0), jan(15, 12));
        complaint(quick, "pothole", jan(15, 0));
        // CLOSED is not a fix: never counted, never in the median.
        Long closed = incident("pothole", "CLOSED", 4.0, jan(16, 0), null);
        complaint(closed, "pothole", jan(16, 0));

        List<CategoryExplorerSummaryDto> rows =
                explorerService.categorySummaries(List.of(wardId), FROM, TO);

        CategoryExplorerSummaryDto garbage = byCode(rows, "garbage");
        assertThat(garbage.getComplaintCount()).isEqualTo(2);
        assertThat(garbage.getActiveIncidentCount()).isEqualTo(2);
        assertThat(garbage.getHighPriorityIncidentCount()).isEqualTo(1);
        assertThat(garbage.getWardCount()).isEqualTo(1);
        assertThat(garbage.getResolvedIncidentCount()).isZero();
        assertThat(garbage.getMedianHoursToResolve()).isNull();
        assertThat(garbage.getDefaultDepartmentId()).isEqualTo(departmentId("Sanitation"));

        CategoryExplorerSummaryDto pothole = byCode(rows, "pothole");
        assertThat(pothole.getResolvedIncidentCount()).isEqualTo(2);
        assertThat(pothole.getMedianHoursToResolve()).isEqualTo(30.0);

        // Every active category is a row, even with no data; noise has no owner.
        CategoryExplorerSummaryDto noise = byCode(rows, "noise");
        assertThat(noise.getComplaintCount()).isZero();
        assertThat(noise.getDefaultDepartmentId()).isNull();
    }

    @Test
    @DisplayName("N3: daily counts by report time, split by category, zeros filled")
    void trends() {
        seed();

        TrendDto trend = explorerService.trends("2001-01-09T00:00:00Z", "2001-01-12T00:00:00Z",
                "day", "category", List.of(wardId), null);

        TrendDto.Series garbage = trend.getSeries().stream()
                .filter(s -> "garbage".equals(s.getKey())).findFirst().orElseThrow();
        assertThat(garbage.getPoints()).extracting(TrendDto.Point::getCount).containsExactly(0L, 1L, 1L);
        TrendDto.Series pothole = trend.getSeries().stream()
                .filter(s -> "pothole".equals(s.getKey())).findFirst().orElseThrow();
        assertThat(pothole.getPoints()).extracting(TrendDto.Point::getCount).containsExactly(0L, 1L, 0L);
    }

    @Test
    @DisplayName("E6: ward detail distributions use the band each incident carries")
    void wardDetailDistributions() {
        seed();

        WardDashboardSummaryDto summary = dashboardService.wardSummary(wardId, null, FROM, TO);

        assertThat(summary.getHighPriorityIncidentCount()).isEqualTo(1);
        assertThat(summary.getPriorityDistribution())
                .containsExactly(
                        org.assertj.core.data.MapEntry.entry("low", 1L),
                        org.assertj.core.data.MapEntry.entry("medium", 0L),
                        org.assertj.core.data.MapEntry.entry("high", 1L),
                        org.assertj.core.data.MapEntry.entry("critical", 0L));
        assertThat(summary.getStatusDistribution())
                .containsEntry("open", 1L)
                .containsEntry("in_progress", 1L)
                .containsEntry("resolved", 1L)
                .containsEntry("closed", 0L);
        assertThat(summary.getLastActivityAt()).isEqualTo("2001-01-11T09:00:00Z");
    }

    @Test
    @DisplayName("E2: owningDepartmentId is the officer's assignment, else the category default")
    void owningDepartment() {
        Long unassigned = incident("drainage", "OPEN", 3.0, jan(10, 0), null);
        complaint(unassigned, "drainage", jan(10, 0));
        Long assigned = incident("drainage", "OPEN", 3.0, jan(10, 0), null);
        complaint(assigned, "drainage", jan(10, 0));
        entityManager.createNativeQuery("UPDATE incidents SET department_id = ?1 WHERE id = ?2")
                .setParameter(1, Long.valueOf(departmentId("Public Works")))
                .setParameter(2, assigned)
                .executeUpdate();
        entityManager.clear();

        PageResponse<IncidentDto> page = incidentService.search(IncidentQuery.parse(
                null, null, null, null, null, null, null, marker, null, null, null, null));

        IncidentDto byDefault = page.getItems().stream()
                .filter(i -> i.getId().equals(String.valueOf(unassigned))).findFirst().orElseThrow();
        assertThat(byDefault.getDepartmentId()).isNull();
        assertThat(byDefault.getOwningDepartmentId()).isEqualTo(departmentId("Storm Water Drains"));

        IncidentDto byOfficer = page.getItems().stream()
                .filter(i -> i.getId().equals(String.valueOf(assigned))).findFirst().orElseThrow();
        assertThat(byOfficer.getOwningDepartmentId()).isEqualTo(departmentId("Public Works"));
    }

    @Test
    @DisplayName("E4: the complaint list filters in the query and pages exactly")
    void complaintList() {
        seed();

        PageResponse<ComplaintDto> all = complaintService.list(
                null, null, null, FROM, TO, marker, null, null, null);
        assertThat(all.getTotal()).isEqualTo(3);
        // Newest report first by default.
        assertThat(all.getItems()).extracting(ComplaintDto::getReportedAt)
                .containsExactly("2001-01-11T09:00:00Z", "2001-01-10T09:00:00Z", "2001-01-10T00:00:00Z");

        PageResponse<ComplaintDto> page = complaintService.list(
                null, List.of("garbage"), null, FROM, TO, marker, "asc", 1, 1);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getItems()).singleElement()
                .extracting(ComplaintDto::getReportedAt).isEqualTo("2001-01-11T09:00:00Z");
    }

    @Test
    @DisplayName("N4: boundaries are a FeatureCollection of every active ward, with attribution")
    void boundaries() {
        String geoJson = explorerService.wardBoundaries();
        JsonNode root = new ObjectMapper().readTree(geoJson);

        long activeWards = ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM wards WHERE active AND boundary IS NOT NULL")
                .getSingleResult()).longValue();
        assertThat(root.get("type").asText()).isEqualTo("FeatureCollection");
        assertThat(root.get("features")).hasSize((int) activeWards);
        assertThat(root.get("attribution").asText()).contains("CC BY-SA");
        assertThat(root.get("features").get(0).get("geometry").get("type").asText())
                .isIn("Polygon", "MultiPolygon");
        // Display payload, so size matters: the full-resolution import is ~1.4 MB.
        System.out.printf("ward boundaries payload: %,d bytes%n", geoJson.length());
        assertThat(geoJson.length()).isLessThan(1_500_000);
    }

    private static CategoryExplorerSummaryDto byCode(List<CategoryExplorerSummaryDto> rows, String code) {
        return rows.stream().filter(r -> r.getCategory().equals(code)).findFirst().orElseThrow();
    }

    private String departmentId(String name) {
        return String.valueOf(((Number) entityManager
                .createNativeQuery("SELECT id FROM departments WHERE name = ?1")
                .setParameter(1, name).getSingleResult()).longValue());
    }
}
