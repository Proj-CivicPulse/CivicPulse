package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.dto.copilot.CopilotQueryRequest;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Copilot's grounding packet, built against the real database in a
 * rolled-back transaction. Rows are reported in January 2001 and the packet is
 * windowed to it, so production data (2026) never enters an assertion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CopilotPacketBuilderTest {

    private static final String FROM = "2001-01-01T00:00:00Z";
    private static final String TO = "2001-02-01T00:00:00Z";

    @Autowired private CopilotPacketBuilder builder;
    @Autowired private EntityManager entityManager;

    private long wardId;

    @BeforeEach
    void setUp() {
        wardId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
    }

    private Long incident(String category, double score, String address) {
        String title = "COPILOTTEST-" + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents
                    (ward_id, category, title, status, priority_score, priority_reasons, complaint_count,
                     address, created_at, updated_at, first_reported_at, last_reported_at)
                VALUES (?1, ?2, NULL, 'OPEN', ?3, CAST(?4 AS jsonb), 1, ?5, NOW(), NOW(), ?6, ?6)
                """)
                .setParameter(1, wardId)
                .setParameter(2, category)
                .setParameter(3, score)
                .setParameter(4, "[\"" + title + "\"]")
                .setParameter(5, address)
                .setParameter(6, LocalDateTime.of(2001, 1, 10, 9, 0))
                .executeUpdate();
        Long id = ((Number) entityManager
                .createNativeQuery("SELECT id FROM incidents WHERE priority_reasons::text LIKE ?1")
                .setParameter(1, "%" + title + "%").getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, 'copilot packet test', ?3, 12.9716, 77.5946, 'OPEN',
                        ?4, 'MATCHED', NOW(), NOW(), ?5)
                """)
                .setParameter(1, wardId)
                .setParameter(2, id)
                .setParameter(3, category)
                .setParameter(4, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(5, LocalDateTime.of(2001, 1, 10, 9, 0))
                .executeUpdate();
        return id;
    }

    private static CopilotQueryRequest request(String ward) {
        CopilotQueryRequest r = new CopilotQueryRequest();
        r.setQuestion("what is urgent?");
        r.setWardId(ward);
        r.setFrom(FROM);
        r.setTo(TO);
        return r;
    }

    /** Every "id" anywhere in the packet body. */
    private static List<String> idsIn(Object node) {
        List<String> ids = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id instanceof String s) ids.add(s);
            map.values().forEach(v -> ids.addAll(idsIn(v)));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> ids.addAll(idsIn(v)));
        }
        return ids;
    }

    @Test
    @DisplayName("a ward-scoped packet holds the ward's active incidents, most urgent first, with the same counts as the Explorer")
    void wardScope() {
        Long urgent = incident("garbage", 6.5, "12, 5th Cross Rd, Bengaluru");
        Long minor = incident("garbage", 1.0, "4J24+6C6 Toxic Home, Bengaluru");

        CopilotPacketBuilder.Packet packet = builder.build(request(String.valueOf(wardId)));

        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) packet.body().get("activeIncidents");
        assertThat(active.get("totalActiveInScope")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) active.get("items");
        assertThat(items).extracting(m -> m.get("id"))
                .containsExactly("incident:" + urgent, "incident:" + minor);
        // Titles are null: named by category + first real place, skipping the
        // house number and the leading plus code.
        assertThat(items).extracting(m -> m.get("name"))
                .containsExactly("Solid waste · 5th Cross Rd", "Solid waste · Toxic Home");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) packet.body().get("metrics");
        assertThat(metrics).anySatisfy(m -> {
            assertThat(m.get("id")).isEqualTo("metric:active_incidents");
            assertThat(m.get("value")).isEqualTo(2L);
        });
        assertThat(metrics).anySatisfy(m -> {
            assertThat(m.get("id")).isEqualTo("metric:high_priority_incidents");
            assertThat(m.get("value")).isEqualTo(1L);
        });

        assertThat(packet.scope().getWardId()).isEqualTo(String.valueOf(wardId));
        assertThat(packet.sources()).containsKey("ward:" + wardId);
    }

    @Test
    @DisplayName("every id in the packet is citable, and nothing citable is missing from the packet")
    void sourcesMatchTheBody() {
        incident("garbage", 6.5, "12, 5th Cross Rd, Bengaluru");
        incident("pothole", 3.2, "Outer Ring Rd, Bengaluru");

        CopilotPacketBuilder.Packet city = builder.build(request(null));

        List<String> ids = idsIn(city.body());
        assertThat(ids).isNotEmpty();
        assertThat(city.sources().keySet()).containsAll(ids);
        assertThat(ids).containsAll(city.sources().keySet());
        // City scope lists the busiest wards; the seeded ward is among them.
        assertThat(ids).contains("ward:" + wardId);
    }

    @Test
    @DisplayName("an unknown or retired ward is a 404 before any model is called")
    void unknownWard() {
        assertThatThrownBy(() -> builder.build(request("999999999")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("place names skip house numbers and plus codes")
    void placeNames() {
        assertThat(CopilotPacketBuilder.placeName("1, OLd Gurappanapalya, BTM 1st Stage")).isEqualTo("OLd Gurappanapalya");
        assertThat(CopilotPacketBuilder.placeName("3JV2+663, Bengaluru")).isEqualTo("Bengaluru");
        assertThat(CopilotPacketBuilder.placeName("4J24+6C6 Toxic Home, Bengaluru")).isEqualTo("Toxic Home");
        assertThat(CopilotPacketBuilder.placeName(null)).isNull();
    }
}
