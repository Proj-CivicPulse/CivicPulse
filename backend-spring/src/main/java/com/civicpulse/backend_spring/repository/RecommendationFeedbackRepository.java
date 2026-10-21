package com.civicpulse.backend_spring.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The recommendation_feedback log (V22). Append-only: a decision is never
 * edited, a later one is simply newer.
 */
@Repository
@RequiredArgsConstructor
public class RecommendationFeedbackRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public record Row(String decision, String recommendedAction, String chosenAction, String note,
                      long userId, LocalDateTime createdAt) {
    }

    public record ActionCounts(String recommendedAction, long accepted, long overridden) {
    }

    public void insert(long incidentId, String recommendedAction, String ruleId, String decision,
                       String chosenAction, String note, long userId, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO recommendation_feedback
                    (incident_id, recommended_action, rule_id, decision, chosen_action, note, user_id, created_at)
                VALUES (:incident, :recommended, :rule, :decision, :chosen, :note, :user, :at)
                """, new MapSqlParameterSource()
                .addValue("incident", incidentId)
                .addValue("recommended", recommendedAction)
                .addValue("rule", ruleId)
                .addValue("decision", decision)
                .addValue("chosen", chosenAction)
                .addValue("note", note)
                .addValue("user", userId)
                .addValue("at", Timestamp.valueOf(at)));
    }

    public Row latest(long incidentId) {
        List<Row> rows = jdbc.query("""
                SELECT decision, recommended_action, chosen_action, note, user_id, created_at
                FROM recommendation_feedback
                WHERE incident_id = :incident
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, new MapSqlParameterSource("incident", incidentId),
                (rs, n) -> new Row(rs.getString("decision"), rs.getString("recommended_action"),
                        rs.getString("chosen_action"), rs.getString("note"), rs.getLong("user_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Decisions per recommended action — the override rate is the Phase 11 metric. */
    public List<ActionCounts> counts() {
        return jdbc.query("""
                SELECT recommended_action,
                       COUNT(*) FILTER (WHERE decision = 'ACCEPTED')   AS accepted,
                       COUNT(*) FILTER (WHERE decision = 'OVERRIDDEN') AS overridden
                FROM recommendation_feedback
                GROUP BY recommended_action
                ORDER BY recommended_action
                """, new MapSqlParameterSource(),
                (rs, n) -> new ActionCounts(rs.getString("recommended_action"),
                        rs.getLong("accepted"), rs.getLong("overridden")));
    }
}
