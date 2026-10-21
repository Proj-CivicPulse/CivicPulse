package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.dto.dashboard.RecommendationSummaryDto;
import com.civicpulse.backend_spring.dto.incident.RecommendationDto;
import com.civicpulse.backend_spring.dto.incident.RecommendationFeedbackRequest;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recommendations and the feedback log against the real database, rolled back
 * afterwards. The clock is fixed in 2001, so real rows are in its future.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecommendationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2001, 2, 1, 0, 0);

    @Autowired private RecommendationService recommendationService;
    @Autowired private EntityManager entityManager;

    private String marker;
    private long wardId;
    private long userId;

    @BeforeEach
    void setUp() {
        marker = "RECOTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO users (name, email, password_hash, role, created_at, updated_at)
                VALUES ('Test Officer', ?1, 'x', 'OFFICER', NOW(), NOW())
                """).setParameter(1, marker.toLowerCase() + "@test.invalid").executeUpdate();
        userId = ((Number) entityManager.createNativeQuery("SELECT id FROM users WHERE email = ?1")
                .setParameter(1, marker.toLowerCase() + "@test.invalid").getSingleResult()).longValue();
    }

    private long incident(String status, double score, LocalDateTime... reports) {
        String title = marker + " " + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents (ward_id, category, title, status, priority_score, latitude, longitude,
                                       created_at, updated_at)
                VALUES (?1, 'drainage', ?2, ?3, ?4, 12.9716, 77.5946, NOW(), NOW())
                """)
                .setParameter(1, wardId).setParameter(2, title).setParameter(3, status).setParameter(4, score)
                .executeUpdate();
        long id = ((Number) entityManager.createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
        for (LocalDateTime at : reports) {
            entityManager.createNativeQuery("""
                    INSERT INTO complaints
                        (ward_id, incident_id, description, category, latitude, longitude, status,
                         reference_no, matching_status, created_at, updated_at, reported_at)
                    VALUES (?1, ?2, ?3, 'drainage', 12.9716, 77.5946, 'OPEN', ?4, 'MATCHED', NOW(), NOW(), ?5)
                    """)
                    .setParameter(1, wardId).setParameter(2, id).setParameter(3, marker)
                    .setParameter(4, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                    .setParameter(5, at)
                    .executeUpdate();
        }
        return id;
    }

    @Test
    @DisplayName("facts come from the members; an urgent incident left open 10 days is escalated")
    void recommends() {
        long id = incident("OPEN", 6.2, NOW.minusDays(10), NOW.minusHours(30));

        RecommendationDto dto = recommendationService.recommend(id, NOW);

        assertThat(dto.getAction()).isEqualTo("escalate");
        assertThat(dto.getRuleId()).isEqualTo("urgent_unattended");
        assertThat(dto.getFacts()).contains("First reported: 10 days ago", "Last reported: 1 day ago",
                "Reports in the last 72 h: 1", "In a hotspot: no");
        assertThat(dto.getAsOf()).isEqualTo("2001-02-01T00:00:00Z");
        assertThat(dto.getLastFeedback()).isNull();
    }

    @Test
    @DisplayName("a resolved incident has no recommendation, and cannot be answered")
    void inactive() {
        long id = incident("RESOLVED", 6.2, NOW.minusDays(3));

        RecommendationDto dto = recommendationService.recommend(id, NOW);
        assertThat(dto.getAction()).isNull();
        assertThat(dto.getExplanation()).contains("resolved");

        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("accepted", null, null), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> recommendationService.recommend(-1L, NOW))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("feedback records what the SERVER recommended, and the summary counts it")
    void feedbackAndSummary() {
        long before = recommendationService.summary().getAccepted() + recommendationService.summary().getOverridden();
        long monitor = incident("OPEN", 2.0, NOW.minusDays(1));
        long dispatch = incident("OPEN", 2.0, NOW.minusHours(10), NOW.minusHours(5));

        RecommendationDto accepted = recommendationService.feedback(monitor, userId,
                new RecommendationFeedbackRequest("accepted", null, null), NOW);
        assertThat(accepted.getAction()).isEqualTo("monitor");
        assertThat(accepted.getLastFeedback().getDecision()).isEqualTo("accepted");
        assertThat(accepted.getLastFeedback().getRecommendedAction()).isEqualTo("monitor");
        assertThat(accepted.getLastFeedback().getUserId()).isEqualTo(String.valueOf(userId));

        RecommendationDto overridden = recommendationService.feedback(dispatch, userId,
                new RecommendationFeedbackRequest("overridden", "monitor", "  crew already on site  "), NOW);
        assertThat(overridden.getAction()).isEqualTo("dispatch");
        assertThat(overridden.getLastFeedback().getChosenAction()).isEqualTo("monitor");
        assertThat(overridden.getLastFeedback().getNote()).isEqualTo("crew already on site");

        RecommendationSummaryDto summary = recommendationService.summary();
        assertThat(summary.getAccepted() + summary.getOverridden()).isEqualTo(before + 2);
        assertThat(summary.getActions()).anySatisfy(r -> {
            assertThat(r.getAction()).isEqualTo("dispatch");
            assertThat(r.getOverridden()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    @DisplayName("an override must name a different, real action; accepted takes no action")
    void validation() {
        long id = incident("OPEN", 2.0, NOW.minusHours(10), NOW.minusHours(5));   // dispatch

        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("overridden", null, null), NOW))
                .isInstanceOf(ValidationException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("overridden", "dispatch", null), NOW))
                .isInstanceOf(ValidationException.class).hasMessageContaining("matches");
        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("overridden", "teleport", null), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("accepted", "monitor", null), NOW))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> recommendationService.feedback(id, userId,
                new RecommendationFeedbackRequest("maybe", null, null), NOW))
                .isInstanceOf(ValidationException.class);
    }
}
