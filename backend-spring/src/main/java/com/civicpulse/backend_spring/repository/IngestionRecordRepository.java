package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.IngestionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IngestionRecordRepository extends JpaRepository<IngestionRecord, Long> {

    /**
     * The most recent decision about one upstream record.
     *
     * <p>This is the idempotency lookup: it answers "have we seen this before,
     * and was it the same?" without touching the complaints table.
     */
    Optional<IngestionRecord> findFirstBySourceAndSourceRecordIdOrderByIdDesc(
            String source, String sourceRecordId);
}
