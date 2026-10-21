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
 * Hotspot clustering (docs/roadmap.md, Phase 9), done by PostGIS.
 *
 * <p>{@code ST_ClusterDBSCAN} over incident centres, partitioned by category,
 * in UTM zone 43N (EPSG:32643) so {@code eps} is in metres — Bengaluru sits at
 * 77.6°E, inside the zone's 72–78°E band. DBSCAN rather than "pairwise within
 * 500 m" because it is the standard, explainable definition: a cluster is a
 * chain of incidents each within {@code eps} of a core point that has at least
 * {@code minPoints} neighbours, and an incident is in at most one cluster.
 */
@Repository
@RequiredArgsConstructor
public class HotspotRepository {

    /** UTM 43N: metres, and undistorted at this latitude. */
    static final int METRIC_SRID = 32643;

    private final NamedParameterJdbcTemplate jdbc;

    /** One clustered incident. {@code clusterId} is only unique within its category. */
    public record Member(String category, int clusterId, long incidentId, long wardId, String wardName,
                         int complaintCount, double priorityScore, double latitude, double longitude,
                         String address, LocalDateTime lastReportedAt) {
    }

    /**
     * Active incidents that fall in a cluster. An incident takes part when it
     * has a centre and a member complaint reported in {@code [from, to)} —
     * the same "in the window" rule as every other Explorer aggregate.
     * Incidents that are noise (no cluster) are not returned.
     */
    public List<Member> clusteredIncidents(List<String> categories, LocalDateTime from, LocalDateTime to,
                                           double epsMetres, int minPoints) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("eps", epsMetres)
                .addValue("minPoints", minPoints)
                .addValue("srid", METRIC_SRID);

        List<String> clauses = new ArrayList<>();
        clauses.add("i.status IN ('OPEN', 'IN_PROGRESS')");
        clauses.add("i.latitude IS NOT NULL AND i.longitude IS NOT NULL");
        if (!categories.isEmpty()) {
            clauses.add("i.category IN (:categories)");
            params.addValue("categories", categories);
        }
        StringBuilder window = new StringBuilder("EXISTS (SELECT 1 FROM complaints m WHERE m.incident_id = i.id");
        if (from != null) {
            window.append(" AND m.reported_at >= :from");
            params.addValue("from", Timestamp.valueOf(from));
        }
        if (to != null) {
            window.append(" AND m.reported_at < :to");
            params.addValue("to", Timestamp.valueOf(to));
        }
        clauses.add(window.append(")").toString());

        String sql = """
                WITH pts AS (
                    SELECT i.id, i.category, i.ward_id, i.complaint_count, i.priority_score,
                           i.latitude, i.longitude, i.address, i.last_reported_at,
                           ST_Transform(ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326), :srid) AS g
                    FROM incidents i
                    WHERE %s
                ),
                clustered AS (
                    SELECT pts.*,
                           ST_ClusterDBSCAN(g, CAST(:eps AS double precision), CAST(:minPoints AS integer))
                               OVER (PARTITION BY category) AS cluster_id
                    FROM pts
                )
                SELECT c.category, c.cluster_id, c.id, c.ward_id, w.name AS ward_name,
                       c.complaint_count, c.priority_score, c.latitude, c.longitude,
                       c.address, c.last_reported_at
                FROM clustered c
                JOIN wards w ON w.id = c.ward_id
                WHERE c.cluster_id IS NOT NULL
                ORDER BY c.category, c.cluster_id, c.id
                """.formatted(String.join(" AND ", clauses));

        return jdbc.query(sql, params, (rs, n) -> {
            Timestamp last = rs.getTimestamp("last_reported_at");
            return new Member(
                    rs.getString("category"), rs.getInt("cluster_id"), rs.getLong("id"),
                    rs.getLong("ward_id"), rs.getString("ward_name"), rs.getInt("complaint_count"),
                    rs.getDouble("priority_score"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                    rs.getString("address"), last == null ? null : last.toLocalDateTime());
        });
    }
}
