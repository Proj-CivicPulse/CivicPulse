package com.civicpulse.backend_spring.entity;

import com.civicpulse.backend_spring.enums.IngestionOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One record as it arrived, and what was decided about it.
 *
 * <p>The raw payload is stored BEFORE validation runs. A gate that discards what
 * it refuses leaves an operator with a count and no way to act on it; the
 * record someone needs to look at is precisely the one that failed.
 */
@Entity
@Table(name = "ingestion_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    /** Null when the feed sent none — itself a rejection reason. */
    @Column(name = "source_record_id", length = 128)
    private String sourceRecordId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> raw;

    /**
     * Hash of the raw payload. Distinguishes a re-delivery of an unchanged
     * record (skip) from an upstream edit (update), without re-comparing every
     * field.
     */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IngestionOutcome status;

    /** Machine-readable, queryable, and kept — see the ingestion_rejections view. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejection_reasons", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, String>> rejectionReasons = new ArrayList<>();

    /** The complaint it became, when accepted. */
    @Column(name = "complaint_id")
    private Long complaintId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
