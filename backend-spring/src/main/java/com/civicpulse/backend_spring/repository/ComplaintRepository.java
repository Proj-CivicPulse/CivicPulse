package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByUserId(Long userId);
    List<Complaint> findByWardId(Long wardId);
    List<Complaint> findByIncidentId(Long incidentId);
    List<Complaint> findByStatus(ComplaintStatus status);
}
