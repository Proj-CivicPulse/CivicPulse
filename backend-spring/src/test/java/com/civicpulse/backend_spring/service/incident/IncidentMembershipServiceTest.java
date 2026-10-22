package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.incident.GroupingDto;
import com.civicpulse.backend_spring.dto.incident.MembershipChangeDto;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.exception.ResourceNotFoundException;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IncidentMatchLogRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Officer merge and unlink, and the grouping explanation, against the real
 * database and rolled back afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentMembershipServiceTest {

    private static final double LAT = 12.9716;
    private static final double LNG = 77.5946;

    @Autowired private IncidentMembershipService membershipService;
    @Autowired private GroupingExplainer groupingExplainer;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private ComplaintRepository complaintRepository;
    @Autowired private IncidentMatchLogRepository matchLogRepository;
    @Autowired private EntityManager entityManager;

    private String marker;
    private long wardId;
    private long officerId;

    @BeforeEach
    void setUp() {
        marker = "MEMBERTEST-" + UUID.randomUUID().toString().substring(0, 8);
        wardId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO users (name, email, password_hash, role, created_at, updated_at)
                VALUES ('Test Officer', ?1, 'x', 'OFFICER', NOW(), NOW())
                """).setParameter(1, marker.toLowerCase() + "@test.invalid").executeUpdate();
        officerId = ((Number) entityManager.createNativeQuery("SELECT id FROM users WHERE email = ?1")
                .setParameter(1, marker.toLowerCase() + "@test.invalid").getSingleResult()).longValue();
    }

    private long incident(String category, String status) {
        String title = marker + " " + UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incidents (ward_id, category, title, status, priority_score, complaint_count,
                                       latitude, longitude, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, 1.0, 0, ?5, ?6, NOW(), NOW())
                """)
                .setParameter(1, wardId).setParameter(2, category).setParameter(3, title).setParameter(4, status)
                .setParameter(5, LAT).setParameter(6, LNG)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM incidents WHERE title = ?1")
                .setParameter(1, title).getSingleResult()).longValue();
    }

    private long report(long incidentId, String category, String text, double northMetres) {
        String ref = "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, incident_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'OPEN', ?7, 'MATCHED', NOW(), NOW(), NOW())
                """)
                .setParameter(1, wardId).setParameter(2, incidentId).setParameter(3, text)
                .setParameter(4, category).setParameter(5, LAT + northMetres / 111_320.0).setParameter(6, LNG)
                .setParameter(7, ref)
                .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM complaints WHERE reference_no = ?1")
                .setParameter(1, ref).getSingleResult()).longValue();
    }

    private List<IncidentMatchLog> log(long complaintId) {
        return matchLogRepository.findByComplaintIdOrderByCreatedAtAsc(complaintId);
    }

    @Test
    @DisplayName("unlink: the report gets its own incident, both are recomputed, the log names the officer")
    void unlink() {
        long id = incident("drainage", "OPEN");
        report(id, "drainage", marker + " Drain blocked near the school", 0);
        report(id, "drainage", marker + " Drain overflowing after rain", 20);
        long manhole = report(id, "drainage", marker + " Open manhole on the corner, no barricade", 40);

        MembershipChangeDto result = membershipService.unlink(id, manhole, officerId, "  different street  ");
        entityManager.flush();

        assertThat(result.getSource().getComplaintCount()).isEqualTo(2);
        assertThat(result.getSource().isLifeSafety()).isFalse();
        assertThat(result.getTarget().getComplaintCount()).isEqualTo(1);
        assertThat(result.getTarget().getId()).isNotEqualTo(String.valueOf(id));
        assertThat(result.getTarget().isLifeSafety()).isTrue();      // the hazard moved with its report

        Complaint moved = complaintRepository.findById(manhole).orElseThrow();
        assertThat(moved.getMatchingStatus()).isEqualTo(MatchingStatus.MATCHED);
        IncidentMatchLog row = log(manhole).getLast();
        assertThat(row.getDecision()).isEqualTo(MatchOutcome.UNLINKED);
        assertThat(row.getMatcher()).isEqualTo(Matcher.OFFICER);
        assertThat(row.isCreated()).isTrue();
        assertThat(row.getPreviousIncidentId()).isEqualTo(id);
        assertThat(row.getActorUserId()).isEqualTo(officerId);
        assertThat(row.getNote()).isEqualTo("different street");

        GroupingDto grouping = groupingExplainer.explain(Long.valueOf(result.getTarget().getId()));
        assertThat(grouping.getMembers()).singleElement().satisfies(m -> {
            assertThat(m.getKind()).isEqualTo("split");
            assertThat(m.getExplanation()).isEqualTo("An officer split this report off incident " + id
                    + " as a different problem. Reason given: different street");
        });
    }

    @Test
    @DisplayName("unlink: refused for the only report, and for a report on another incident")
    void unlinkRefusals() {
        long lone = incident("drainage", "OPEN");
        long only = report(lone, "drainage", marker + " blocked", 0);
        assertThatThrownBy(() -> membershipService.unlink(lone, only, officerId, null))
                .isInstanceOf(ValidationException.class);

        long other = incident("drainage", "OPEN");
        assertThatThrownBy(() -> membershipService.unlink(other, only, officerId, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("merge: every report moves, the emptied incident is deleted, each move is logged")
    void merge() {
        long source = incident("pothole", "OPEN");
        long target = incident("pothole", "IN_PROGRESS");
        List<Long> moved = new ArrayList<>();
        moved.add(report(source, "pothole", marker + " pothole by the bus stop", 0));
        moved.add(report(source, "pothole", marker + " big hole at the stop", 10));
        report(target, "pothole", marker + " crater near the bus stop", 30);

        MembershipChangeDto result = membershipService.merge(source, target, officerId, null);
        entityManager.flush();

        assertThat(result.getSource()).isNull();
        assertThat(result.getTarget().getComplaintCount()).isEqualTo(3);
        assertThat(result.getTarget().getStatus()).isEqualTo("in_progress");
        assertThat(incidentRepository.findById(source)).isEmpty();
        for (long c : moved) {
            IncidentMatchLog row = log(c).getLast();
            assertThat(row.getDecision()).isEqualTo(MatchOutcome.MERGED);
            assertThat(row.getChosenIncidentId()).isEqualTo(target);
            assertThat(row.getPreviousIncidentId()).isEqualTo(source);
        }
        assertThat(groupingExplainer.explain(target).getMembers())
                .filteredOn(m -> m.getKind().equals("merged")).hasSize(2);
    }

    @Test
    @DisplayName("merge: refused across categories, into itself, and for an inactive incident")
    void mergeRefusals() {
        long pothole = incident("pothole", "OPEN");
        long garbage = incident("garbage", "OPEN");
        long resolved = incident("pothole", "RESOLVED");
        report(pothole, "pothole", marker, 0);

        assertThatThrownBy(() -> membershipService.merge(pothole, garbage, officerId, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("same category");
        assertThatThrownBy(() -> membershipService.merge(pothole, pothole, officerId, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> membershipService.merge(pothole, resolved, officerId, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("not active");
    }

    @Test
    @DisplayName("grouping: a matcher join is told as distance and wording, never a score")
    void grouping() {
        long id = incident("streetlight", "OPEN");
        long first = report(id, "streetlight", marker + " light out", 0);
        long second = report(id, "streetlight", marker + " lamp dead", 42);
        long third = report(id, "streetlight", marker + " no light", 5);
        matchLogRepository.save(IncidentMatchLog.builder().complaintId(first).decision(MatchOutcome.CREATED)
                .created(true).matcher(Matcher.SEMANTIC).chosenIncidentId(id).candidateCount(0).build());
        matchLogRepository.save(IncidentMatchLog.builder().complaintId(second).decision(MatchOutcome.MATCHED)
                .matcher(Matcher.SEMANTIC).chosenIncidentId(id).topSiblingComplaintId(first)
                .topSimilarity(0.91).threshold(0.84).candidateCount(1).build());
        matchLogRepository.save(IncidentMatchLog.builder().complaintId(third).decision(MatchOutcome.MATCHED)
                .matcher(Matcher.NAIVE).chosenIncidentId(id).candidateCount(1).build());
        entityManager.flush();

        GroupingDto dto = groupingExplainer.explain(id);
        String ref = complaintRepository.findById(first).orElseThrow().getReferenceNo();

        assertThat(dto.getMembers()).extracting(GroupingDto.Member::getKind)
                .containsExactly("started", "joined", "backup_rule");
        GroupingDto.Member joined = dto.getMembers().get(1);
        assertThat(joined.getExplanation())
                .isEqualTo("Grouped with report " + ref + ": a very similar description, 40 m away.");
        assertThat(joined.getDistanceM()).isEqualTo(42);
        assertThat(dto.getMembers()).allSatisfy(m -> assertThat(m.getExplanation()).doesNotContain("0.9"));
    }
}
