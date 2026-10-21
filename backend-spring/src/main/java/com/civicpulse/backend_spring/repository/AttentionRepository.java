package com.civicpulse.backend_spring.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Queries behind "needs attention" (docs/roadmap.md, Phase 10).
 *
 * <p>Everything is evaluated AS OF {@code now}: an incident counts only if it
 * had a member reported before {@code now}, and every recent-activity window
 * is bounded above by it. In production {@code now} is the clock; in tests it
 * is a fixed instant, which is also what isolates test rows from real ones.
 */
@Repository
@RequiredArgsConstructor
public class AttentionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /** An active incident with its recent-report count. */
    public record ActiveIncident(long id, long wardId, String wardName, String category, String address,
                                 double priorityScore, int complaintCount, long recentReports,
                                 LocalDateTime lastReportedAt) {
    }

    /** One category's report counts: the last 7 days and the 28 days before them. */
    public record CategoryVolume(String category, long lastWeek, long priorFourWeeks) {
    }

    /**
     * Every active incident under the filters, with how many of its members
     * were reported in {@code [recentFrom, now)}.
     */
    public List<ActiveIncident> activeIncidents(List<Long> wardIds, List<String> categories,
                                                LocalDateTime now, LocalDateTime recentFrom) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.valueOf(now))
                .addValue("recentFrom", Timestamp.valueOf(recentFrom));
        List<String> clauses = new ArrayList<>();
        clauses.add("i.status IN ('OPEN', 'IN_PROGRESS')");
        filters("i", wardIds, categories, params, clauses);

        String sql = """
                SELECT i.id, i.ward_id, w.name AS ward_name, i.category, i.address,
                       COALESCE(i.priority_score, 0) AS score, i.complaint_count,
                       COUNT(*) FILTER (WHERE m.reported_at >= :recentFrom) AS recent,
                       MAX(m.reported_at) AS last_reported
                FROM incidents i
                JOIN wards w ON w.id = i.ward_id
                JOIN complaints m ON m.incident_id = i.id AND m.reported_at < :now
                WHERE %s
                GROUP BY i.id, w.name
                """.formatted(String.join(" AND ", clauses));

        return jdbc.query(sql, params, (rs, n) -> {
            Timestamp last = rs.getTimestamp("last_reported");
            return new ActiveIncident(
                    rs.getLong("id"), rs.getLong("ward_id"), rs.getString("ward_name"),
                    rs.getString("category"), rs.getString("address"), rs.getDouble("score"),
                    rs.getInt("complaint_count"), rs.getLong("recent"),
                    last == null ? null : last.toLocalDateTime());
        });
    }

    /** Complaint counts per category in [now-7d, now) and [now-35d, now-7d). */
    public List<CategoryVolume> categoryVolumes(List<Long> wardIds, List<String> categories, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.valueOf(now))
                .addValue("weekAgo", Timestamp.valueOf(now.minusDays(7)))
                .addValue("fiveWeeksAgo", Timestamp.valueOf(now.minusDays(35)));
        List<String> clauses = new ArrayList<>();
        clauses.add("c.reported_at >= :fiveWeeksAgo AND c.reported_at < :now");
        filters("c", wardIds, categories, params, clauses);

        String sql = """
                SELECT c.category,
                       COUNT(*) FILTER (WHERE c.reported_at >= :weekAgo) AS last_week,
                       COUNT(*) FILTER (WHERE c.reported_at <  :weekAgo) AS prior
                FROM complaints c
                WHERE %s
                GROUP BY c.category
                """.formatted(String.join(" AND ", clauses));

        return jdbc.query(sql, params, (rs, n) -> new CategoryVolume(
                rs.getString("category"), rs.getLong("last_week"), rs.getLong("prior")));
    }

    private static void filters(String alias, List<Long> wardIds, List<String> categories,
                                MapSqlParameterSource params, List<String> clauses) {
        if (!wardIds.isEmpty()) {
            clauses.add(alias + ".ward_id IN (:wardIds)");
            params.addValue("wardIds", wardIds);
        }
        if (!categories.isEmpty()) {
            clauses.add(alias + ".category IN (:categories)");
            params.addValue("categories", categories);
        }
    }
}
