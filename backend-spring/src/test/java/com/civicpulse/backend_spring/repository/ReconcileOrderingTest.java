package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Complaint;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconcile queue must drain in ARRIVAL order, not in reported order.
 *
 * <p>This is a regression test for a specific, quiet failure. The sweep used to
 * order by {@code created_at}, and V15 wrote the upstream report time into that
 * column for imported complaints. A backlog imported this morning therefore
 * carried timestamps months old and sorted to the FRONT of the queue, ahead of
 * complaints residents had filed minutes earlier. At the default 25 records per
 * 60-second sweep, ten thousand imported rows would have held live submissions
 * in {@code DEGRADED} for about seven hours — with nothing failing, nothing
 * logged as wrong, and the naive fallback quietly standing in the whole time.
 *
 * <p>Two things now prevent it: {@code reported_at} is a separate column (V16)
 * so {@code created_at} is never rewritten, and the sweep orders by {@code id},
 * which is arrival order and is not derived from anything a caller sends.
 *
 * <p>Runs against the real database inside a transaction Spring rolls back, so
 * the JPQL is actually executed rather than merely inspected — the ordering
 * clause is the thing under test, and a string assertion would not catch a
 * change to it that still compiled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReconcileOrderingTest {

    @Autowired private ComplaintRepository complaintRepository;
    @Autowired private EntityManager entityManager;

    /**
     * Inserts natively so {@code created_at} can be set to a chosen instant.
     * Through JPA it cannot: {@code @CreationTimestamp} generates it, which is
     * exactly the property that made the old backdating hack necessary.
     */
    private Long insert(String marker, LocalDateTime createdAt, LocalDateTime reportedAt) {
        Object wardId = entityManager
                .createNativeQuery("SELECT id FROM wards WHERE active ORDER BY id LIMIT 1")
                .getSingleResult();

        entityManager.createNativeQuery("""
                INSERT INTO complaints
                    (ward_id, description, category, latitude, longitude, status,
                     reference_no, matching_status, created_at, updated_at, reported_at)
                VALUES (?1, ?2, 'pothole', 12.9716, 77.5946, 'OPEN',
                        ?3, 'PENDING', ?4, ?4, ?5)
                """)
                .setParameter(1, wardId)
                .setParameter(2, marker)
                // Unique per row rather than derived from the marker: the
                // markers deliberately share a prefix, and reference_no is
                // VARCHAR(32) with a unique constraint.
                .setParameter(3, "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24))
                .setParameter(4, createdAt)
                .setParameter(5, reportedAt)
                .executeUpdate();

        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM complaints WHERE description = ?1")
                .setParameter(1, marker)
                .getSingleResult()).longValue();
    }

    @Test
    @DisplayName("a backdated import does not jump ahead of a complaint filed minutes ago")
    void backdatedImportDoesNotJumpTheQueue() {
        LocalDateTime now = LocalDateTime.now();
        String tag = "reconcile-order-" + System.nanoTime();

        // Filed by a resident first, so it arrives first and gets the lower id.
        Long live = insert(tag + "-live", now.minusMinutes(5), now.minusMinutes(5));
        // Imported afterwards, carrying a six-month-old upstream report time.
        Long imported = insert(tag + "-imported", now, now.minusMonths(6));

        entityManager.flush();

        List<Complaint> queue = complaintRepository
                .findReconcileCandidates(now.minusSeconds(45), PageRequest.of(0, 500));

        List<Long> ids = queue.stream().map(Complaint::getId).toList();
        assertThat(ids).contains(live, imported);

        assertThat(ids.indexOf(live))
                .as("the complaint that ARRIVED first must be processed first; "
                        + "the import's six-month-old reported_at must not promote it")
                .isLessThan(ids.indexOf(imported));
    }

    @Test
    @DisplayName("the queue is ordered by id across the whole page, not merely pairwise")
    void queueIsStrictlyArrivalOrdered() {
        LocalDateTime now = LocalDateTime.now();
        String tag = "reconcile-seq-" + System.nanoTime();

        // Reported times deliberately DESCENDING while arrival ascends. If the
        // sweep ordered by any reported/source timestamp, this page would come
        // back exactly reversed.
        for (int i = 0; i < 5; i++) {
            insert(tag + "-" + i, now, now.minusDays(i));
        }
        entityManager.flush();

        List<Long> ids = complaintRepository
                .findReconcileCandidates(now.minusSeconds(45), PageRequest.of(0, 500))
                .stream().map(Complaint::getId).toList();

        assertThat(ids).isSorted();
    }

    @Test
    @DisplayName("reported_at is never overwritten by row creation, and vice versa")
    void theTwoTimestampsStayIndependent() {
        LocalDateTime now = LocalDateTime.now();
        String tag = "reconcile-split-" + System.nanoTime();
        LocalDateTime upstream = now.minusMonths(6).withNano(0);

        Long id = insert(tag, now, upstream);
        entityManager.flush();
        entityManager.clear();

        Complaint loaded = complaintRepository.findById(id).orElseThrow();

        assertThat(loaded.getReportedAt())
                .as("the upstream report time survives")
                .isEqualTo(upstream);
        assertThat(loaded.getCreatedAt())
                .as("row creation is NOT rewritten to the upstream time")
                .isAfter(upstream.plusMonths(5));
    }
}
