package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.repository.projection.WardOpenCountRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface IncidentRepository
        extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    List<Incident> findByWardId(Long wardId);

    List<Incident> findByStatus(IncidentStatus status);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    long countByWardIdAndStatusIn(Long wardId, Collection<IncidentStatus> statuses);

    /**
     * Candidate incidents for the placeholder grouper: same ward, same
     * category, still actionable, and opened recently enough that a new report
     * plausibly belongs to the same problem.
     */
    List<Incident> findByWardIdAndCategoryAndStatusInAndCreatedAtAfter(
            Long wardId,
            String category,
            Collection<IncidentStatus> statuses,
            LocalDateTime since);

    /**
     * One grouped query for the whole ward strip. Wards with no open incidents
     * are absent from the result, so callers default them to zero rather than
     * this query having to left-join every ward.
     */
    @Query("""
            select i.ward.id as wardId, count(i) as openCount
            from Incident i
            where i.status in :statuses
            group by i.ward.id
            """)
    List<WardOpenCountRow> countOpenGroupedByWard(
            @Param("statuses") Collection<IncidentStatus> statuses);
}
