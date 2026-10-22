package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.IncidentMatchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentMatchLogRepository extends JpaRepository<IncidentMatchLog, Long> {

    /** Every decision recorded for one complaint, oldest first — its matching history. */
    List<IncidentMatchLog> findByComplaintIdOrderByCreatedAtAsc(Long complaintId);

    /** Every decision for these complaints, newest first — the first per complaint is its latest. */
    List<IncidentMatchLog> findByComplaintIdInOrderByIdDesc(List<Long> complaintIds);
}
