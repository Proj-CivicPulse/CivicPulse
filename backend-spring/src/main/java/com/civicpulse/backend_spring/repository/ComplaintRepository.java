package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.repository.projection.CategoryCountRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ComplaintRepository
        extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

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
}
