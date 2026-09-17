package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.repository.projection.CategoryCountRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComplaintRepository
        extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

    /**
     * A complaint by its upstream identity.
     *
     * <p>Backed by the partial unique index on (source, source_record_id). This
     * is what makes re-ingesting a feed's file idempotent: the second run finds
     * the row it created the first time instead of writing a twin.
     */
    Optional<Complaint> findBySourceAndSourceRecordId(String source, String sourceRecordId);

    /**
     * Overwrites {@code created_at} with the time the report was actually made
     * upstream.
     *
     * <p>Needed because {@code Complaint.createdAt} carries Hibernate's
     * {@code @CreationTimestamp}, which GENERATES the value on insert and
     * silently discards anything the caller set. For a complaint typed into the
     * website that is exactly right. For one imported from a feed it is wrong in
     * a way nothing downstream can detect: a six-month backlog imported on
     * Tuesday would look like it all arrived on Tuesday, handing every one of
     * those incidents a maximal growth score and a zero age.
     *
     * <p>A NATIVE statement, and deliberately so — the mapping says this column
     * is generated and not updatable, and going around that should look like
     * going around it rather than like an ordinary save. {@code seed-dev-data.mjs}
     * backdates in SQL for the same reason.
     */
    @Modifying
    @Query(value = "UPDATE complaints SET created_at = :reportedAt WHERE id = :id",
            nativeQuery = true)
    void backdateCreatedAt(@Param("id") Long id, @Param("reportedAt") LocalDateTime reportedAt);

    List<Complaint> findByUserId(Long userId);

    List<Complaint> findByWardId(Long wardId);

    List<Complaint> findByIncidentId(Long incidentId);

    List<Complaint> findByStatus(ComplaintStatus status);

    /** Newest first — the order My reports renders in. */
    List<Complaint> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Oldest first: an incident's reports read as a chronology. */
    List<Complaint> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);

    long countByStatus(ComplaintStatus status);

    long countByWardId(Long wardId);

    long countByWardIdAndStatus(Long wardId, ComplaintStatus status);

    long countByCreatedAtAfter(LocalDateTime since);

    long countByWardIdAndCreatedAtAfter(Long wardId, LocalDateTime since);

    /**
     * Recomputed from scratch on every attach rather than incremented, so the
     * operation stays idempotent if a callback is retried.
     */
    long countByIncidentId(Long incidentId);

    long countByIncidentIdAndCreatedAtAfter(Long incidentId, LocalDateTime since);

    @Query("""
            select c.category as category, count(c) as total
            from Complaint c
            where (:wardId is null or c.ward.id = :wardId)
            group by c.category
            order by count(c) desc
            """)
    List<CategoryCountRow> topCategories(@Param("wardId") Long wardId, Pageable page);

    /**
     * The reconcile sweep's candidate set: complaints the semantic pipeline has
     * not settled — PENDING or DEGRADED outright, plus PROCESSING rows whose
     * claim has gone stale (Node crashed mid-run). Oldest first, so the backlog
     * drains in arrival order.
     *
     * <p>{@code staleBefore} is only a filter here — backend-node's compare-and-swap
     * claim is the actual authority on whether a stale row may be re-taken, so a
     * mismatch between the two windows costs at most a wasted {@code skipped}
     * round-trip.
     */
    @Query("""
            select c from Complaint c
            where c.matchingStatus in (com.civicpulse.backend_spring.enums.MatchingStatus.PENDING,
                                       com.civicpulse.backend_spring.enums.MatchingStatus.DEGRADED)
               or (c.matchingStatus = com.civicpulse.backend_spring.enums.MatchingStatus.PROCESSING
                   and c.updatedAt < :staleBefore)
            order by c.createdAt asc
            """)
    List<Complaint> findReconcileCandidates(
            @Param("staleBefore") LocalDateTime staleBefore, Pageable page);

    /** Size of that same backlog, logged each reconcile run so an outage can't pile up unseen. */
    @Query("""
            select count(c) from Complaint c
            where c.matchingStatus in (com.civicpulse.backend_spring.enums.MatchingStatus.PENDING,
                                       com.civicpulse.backend_spring.enums.MatchingStatus.DEGRADED)
               or (c.matchingStatus = com.civicpulse.backend_spring.enums.MatchingStatus.PROCESSING
                   and c.updatedAt < :staleBefore)
            """)
    long countReconcileBacklog(@Param("staleBefore") LocalDateTime staleBefore);

    long countByMatchingStatus(MatchingStatus matchingStatus);
}
