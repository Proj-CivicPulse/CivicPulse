package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.service.explorer.ExplorerFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The Explorer's aggregate queries (docs/api-contract.md, N1–N4 and E6).
 *
 * <p>Native SQL on purpose. These are GROUP BYs with FILTER clauses,
 * percentile_cont, DISTINCT ON and PostGIS — the database computes each figure
 * in one pass, where JPQL could express only some of it and the rest would be
 * entity loads summed in Java.
 *
 * <p>Filters are appended as fixed SQL fragments with bound parameters, never
 * by concatenating a value: the fragment text is chosen by which filters are
 * present, and every value goes through {@link MapSqlParameterSource}.
 *
 * <p>Definitions are the ones in docs/data-quality-rules.md §3 and are not
 * restated per query: active = OPEN or IN_PROGRESS; high priority = active and
 * {@code priority_score >= highThreshold}, the threshold passed in from
 * PriorityBand so it is never a second copy; date windows are on complaints'
 * {@code reported_at}; an incident is "in" a window when one of its members was
 * reported in it.
 */
@Repository
@RequiredArgsConstructor
public class ExplorerRepository {

    private static final String ACTIVE = "('OPEN', 'IN_PROGRESS')";

    private final NamedParameterJdbcTemplate jdbc;

    // ── Rows ────────────────────────────────────────────────────────────────

    public record WardRow(long wardId, String wardCode, String wardName, long complaintCount,
                          long activeIncidentCount, long highPriorityIncidentCount,
                          String dominantCategory, LocalDateTime lastActivityAt) {
    }

    public record CategoryRow(String category, long complaintCount, long activeIncidentCount,
                              long highPriorityIncidentCount, long wardCount,
                              long resolvedIncidentCount, Double medianHoursToResolve,
                              Long defaultDepartmentId, LocalDateTime lastActivityAt) {
    }

    public record BucketCount(LocalDateTime bucket, String key, long count) {
    }

    public record IncidentState(String status, double priorityScore) {
    }

    // ── N1: one row per active ward ─────────────────────────────────────────

    /** Honours {@code categories} and the window; {@code wardIds} is ignored — every active ward is a row. */
    public List<WardRow> wardSummaries(ExplorerFilter filter, double highThreshold) {
        MapSqlParameterSource params = new MapSqlParameterSource("high", highThreshold);
        String complaintWhere = complaintWhere("c", filter, params, false);
        String incidentWhere = incidentWhere("i", filter, params, false);

        String sql = """
                WITH c AS (
                    SELECT c.ward_id, c.category, c.reported_at
                    FROM complaints c
                    WHERE %s
                ),
                complaint_agg AS (
                    SELECT ward_id, COUNT(*) AS complaints, MAX(reported_at) AS last_activity
                    FROM c GROUP BY ward_id
                ),
                dominant AS (
                    -- Most complaints wins; ties go to registry order, then code,
                    -- so the answer is stable between requests.
                    SELECT DISTINCT ON (c.ward_id) c.ward_id, c.category
                    FROM c LEFT JOIN categories cat ON cat.code = c.category
                    GROUP BY c.ward_id, c.category, cat.sort_order
                    ORDER BY c.ward_id, COUNT(*) DESC, cat.sort_order NULLS LAST, c.category
                ),
                incident_agg AS (
                    SELECT i.ward_id,
                           COUNT(*) FILTER (WHERE i.status IN %s) AS active,
                           COUNT(*) FILTER (WHERE i.status IN %s
                                            AND COALESCE(i.priority_score, 0) >= :high) AS high
                    FROM incidents i
                    WHERE %s
                    GROUP BY i.ward_id
                )
                SELECT w.id, w.code, w.name,
                       COALESCE(ca.complaints, 0)  AS complaints,
                       COALESCE(ia.active, 0)      AS active,
                       COALESCE(ia.high, 0)        AS high,
                       d.category                  AS dominant,
                       ca.last_activity
                FROM wards w
                LEFT JOIN complaint_agg ca ON ca.ward_id = w.id
                LEFT JOIN incident_agg  ia ON ia.ward_id = w.id
                LEFT JOIN dominant      d  ON d.ward_id  = w.id
                WHERE w.active
                ORDER BY CASE WHEN w.code ~ '^[0-9]+$' THEN w.code::int END, w.code
                """.formatted(complaintWhere, ACTIVE, ACTIVE, incidentWhere);

        return jdbc.query(sql, params, (rs, n) -> new WardRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getLong("complaints"), rs.getLong("active"), rs.getLong("high"),
                rs.getString("dominant"), timestamp(rs, "last_activity")));
    }

    // ── N2: one row per category that is active or has data ─────────────────

    /**
     * Honours {@code wardIds} and the window. Resolution figures count
     * incidents RESOLVED (never CLOSED) whose {@code resolved_at} falls in the
     * window; time to resolve is {@code resolved_at - first_reported_at}.
     */
    public List<CategoryRow> categorySummaries(ExplorerFilter filter, double highThreshold) {
        MapSqlParameterSource params = new MapSqlParameterSource("high", highThreshold);
        String complaintWhere = complaintWhere("c", filter, params, true);
        String incidentWhere = incidentWhere("i", filter, params, true);
        String resolvedWhere = resolvedWhere("r", filter, params);

        String sql = """
                WITH complaint_agg AS (
                    SELECT c.category,
                           COUNT(*)                  AS complaints,
                           COUNT(DISTINCT c.ward_id) AS wards,
                           MAX(c.reported_at)        AS last_activity
                    FROM complaints c
                    WHERE %s
                    GROUP BY c.category
                ),
                incident_agg AS (
                    SELECT i.category,
                           COUNT(*) FILTER (WHERE i.status IN %s) AS active,
                           COUNT(*) FILTER (WHERE i.status IN %s
                                            AND COALESCE(i.priority_score, 0) >= :high) AS high
                    FROM incidents i
                    WHERE %s
                    GROUP BY i.category
                ),
                resolved_agg AS (
                    SELECT r.category,
                           COUNT(*) AS resolved,
                           PERCENTILE_CONT(0.5) WITHIN GROUP (
                               ORDER BY EXTRACT(EPOCH FROM (r.resolved_at - r.first_reported_at)) / 3600.0
                           ) AS median_hours
                    FROM incidents r
                    WHERE r.status = 'RESOLVED'
                      AND r.resolved_at IS NOT NULL
                      AND r.first_reported_at IS NOT NULL
                      AND %s
                    GROUP BY r.category
                ),
                codes AS (
                    SELECT code FROM categories WHERE active
                    UNION SELECT category FROM complaint_agg
                    UNION SELECT category FROM incident_agg
                    UNION SELECT category FROM resolved_agg
                )
                SELECT k.code,
                       COALESCE(ca.complaints, 0) AS complaints,
                       COALESCE(ia.active, 0)     AS active,
                       COALESCE(ia.high, 0)       AS high,
                       COALESCE(ca.wards, 0)      AS wards,
                       COALESCE(ra.resolved, 0)   AS resolved,
                       ra.median_hours,
                       cat.default_department_id,
                       ca.last_activity
                FROM codes k
                LEFT JOIN categories    cat ON cat.code     = k.code
                LEFT JOIN complaint_agg ca  ON ca.category  = k.code
                LEFT JOIN incident_agg  ia  ON ia.category  = k.code
                LEFT JOIN resolved_agg  ra  ON ra.category  = k.code
                ORDER BY cat.sort_order NULLS LAST, k.code
                """.formatted(complaintWhere, ACTIVE, ACTIVE, incidentWhere, resolvedWhere);

        return jdbc.query(sql, params, (rs, n) -> new CategoryRow(
                rs.getString("code"),
                rs.getLong("complaints"), rs.getLong("active"), rs.getLong("high"),
                rs.getLong("wards"), rs.getLong("resolved"),
                nullableDouble(rs, "median_hours"),
                nullableLong(rs, "default_department_id"),
                timestamp(rs, "last_activity")));
    }

    // ── N3: complaint counts per bucket ─────────────────────────────────────

    /**
     * Counts by {@code date_trunc(unit, reported_at, 'UTC')} — UTC days and
     * weeks, matching the service's bucket list — optionally split by
     * category. Only non-empty buckets come back; the service fills the zeros,
     * because it owns the bucket list. {@code unit} is "day" or "week" — chosen
     * by the service from an enum, never from request text.
     */
    public List<BucketCount> complaintCounts(ExplorerFilter filter, String unit, boolean byCategory) {
        if (!unit.equals("day") && !unit.equals("week")) {
            throw new IllegalArgumentException("unit must be day or week");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("unit", unit);
        String where = complaintWhere("c", filter, params, true);
        String key = byCategory ? "c.category" : "NULL::varchar";

        String sql = """
                SELECT date_trunc(:unit, c.reported_at, 'UTC') AS bucket, %s AS key, COUNT(*) AS n
                FROM complaints c
                WHERE %s
                GROUP BY 1, 2
                """.formatted(key, where);

        return jdbc.query(sql, params, (rs, n) -> new BucketCount(
                timestamp(rs, "bucket"), rs.getString("key"), rs.getLong("n")));
    }

    // ── E6: per-ward incident states ────────────────────────────────────────

    /**
     * Status and score of every incident in one ward that passes the filters.
     * Bands are assigned in Java with PriorityBand.of, so the distribution uses
     * exactly the classification the Incident shape carries.
     */
    public List<IncidentState> incidentStates(long wardId, ExplorerFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource("ward", wardId);
        String where = incidentWhere("i", filter, params, false);
        String sql = """
                SELECT i.status, COALESCE(i.priority_score, 0) AS score
                FROM incidents i
                WHERE i.ward_id = :ward AND %s
                """.formatted(where);
        return jdbc.query(sql, params, (rs, n) ->
                new IncidentState(rs.getString("status"), rs.getDouble("score")));
    }

    /** Most recent complaint report in a ward under the filters; null if none. */
    public LocalDateTime lastActivity(long wardId, ExplorerFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource("ward", wardId);
        String where = complaintWhere("c", filter, params, false);
        String sql = "SELECT MAX(c.reported_at) AS last FROM complaints c WHERE c.ward_id = :ward AND " + where;
        return jdbc.query(sql, params, rs -> rs.next() ? timestamp(rs, "last") : null);
    }

    // ── N4: ward boundaries as GeoJSON ──────────────────────────────────────

    /**
     * Active ward boundaries as one GeoJSON FeatureCollection, built by
     * PostGIS. Simplified to ~10 m for display; ward ASSIGNMENT never uses
     * this, it stays ST_Contains on the full geometry. Six decimal places
     * (~0.11 m) is far below the simplification tolerance, so it loses nothing.
     */
    public String wardBoundariesGeoJson(double toleranceDegrees) {
        String sql = """
                SELECT json_build_object(
                    'type', 'FeatureCollection',
                    'datasetVersion', MAX(w.dataset_version),
                    'attribution', 'KSRSAC / DataMeet, CC BY-SA 2.5 India',
                    'features', COALESCE(json_agg(json_build_object(
                        'type', 'Feature',
                        'geometry', ST_AsGeoJSON(
                            ST_SimplifyPreserveTopology(w.boundary, :tolerance), 6)::json,
                        'properties', json_build_object(
                            'id', w.id::text, 'code', w.code, 'name', w.name)
                    ) ORDER BY w.id), '[]'::json)
                )::text
                FROM wards w
                WHERE w.active AND w.boundary IS NOT NULL
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("tolerance", toleranceDegrees), String.class);
    }

    // ── Filter fragments ────────────────────────────────────────────────────

    /** Complaint-side filter: categories, the reported_at window, and optionally wards. */
    private static String complaintWhere(String alias, ExplorerFilter filter,
                                         MapSqlParameterSource params, boolean applyWards) {
        List<String> clauses = new ArrayList<>();
        clauses.add("TRUE");
        if (applyWards && !filter.wardIds().isEmpty()) {
            clauses.add(alias + ".ward_id IN (:wardIds)");
            params.addValue("wardIds", filter.wardIds());
        }
        if (!filter.categories().isEmpty()) {
            clauses.add(alias + ".category IN (:categories)");
            params.addValue("categories", filter.categories());
        }
        if (filter.from() != null) {
            clauses.add(alias + ".reported_at >= :from");
            params.addValue("from", Timestamp.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            clauses.add(alias + ".reported_at < :to");
            params.addValue("to", Timestamp.valueOf(filter.to()));
        }
        return String.join(" AND ", clauses);
    }

    /** Incident-side filter: categories, a member reported in the window, and optionally wards. */
    private static String incidentWhere(String alias, ExplorerFilter filter,
                                        MapSqlParameterSource params, boolean applyWards) {
        List<String> clauses = new ArrayList<>();
        clauses.add("TRUE");
        if (applyWards && !filter.wardIds().isEmpty()) {
            clauses.add(alias + ".ward_id IN (:wardIds)");
            params.addValue("wardIds", filter.wardIds());
        }
        if (!filter.categories().isEmpty()) {
            clauses.add(alias + ".category IN (:categories)");
            params.addValue("categories", filter.categories());
        }
        if (filter.hasWindow()) {
            StringBuilder exists = new StringBuilder("EXISTS (SELECT 1 FROM complaints m WHERE m.incident_id = ")
                    .append(alias).append(".id");
            if (filter.from() != null) {
                exists.append(" AND m.reported_at >= :from");
                params.addValue("from", Timestamp.valueOf(filter.from()));
            }
            if (filter.to() != null) {
                exists.append(" AND m.reported_at < :to");
                params.addValue("to", Timestamp.valueOf(filter.to()));
            }
            clauses.add(exists.append(")").toString());
        }
        return String.join(" AND ", clauses);
    }

    /** Resolution filter: wards, categories, and resolved_at in the window. */
    private static String resolvedWhere(String alias, ExplorerFilter filter, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        clauses.add("TRUE");
        if (!filter.wardIds().isEmpty()) {
            clauses.add(alias + ".ward_id IN (:wardIds)");
            params.addValue("wardIds", filter.wardIds());
        }
        if (!filter.categories().isEmpty()) {
            clauses.add(alias + ".category IN (:categories)");
            params.addValue("categories", filter.categories());
        }
        if (filter.from() != null) {
            clauses.add(alias + ".resolved_at >= :from");
            params.addValue("from", Timestamp.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            clauses.add(alias + ".resolved_at < :to");
            params.addValue("to", Timestamp.valueOf(filter.to()));
        }
        return String.join(" AND ", clauses);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
