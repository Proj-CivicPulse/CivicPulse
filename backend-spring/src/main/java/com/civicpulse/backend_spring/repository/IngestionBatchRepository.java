package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.IngestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, Long> {

    List<IngestionBatch> findBySourceOrderByStartedAtDesc(String source);
}
