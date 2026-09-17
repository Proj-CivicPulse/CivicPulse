package com.civicpulse.backend_spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One run of one external feed, with the counts that make ingestion
 * observable rather than something that either "worked" or did not.
 *
 * <p>The counts are written once at the end, derived from the record rows, so a
 * run that dies halfway cannot leave a total that disagrees with the records it
 * actually produced.
 */
@Entity
@Table(name = "ingestion_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** Null while the run is in flight. */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "received", nullable = false)
    @Builder.Default
    private int received = 0;

    @Column(name = "accepted", nullable = false)
    @Builder.Default
    private int accepted = 0;

    @Column(name = "rejected", nullable = false)
    @Builder.Default
    private int rejected = 0;

    /** Records already seen and unchanged — the measure of a healthy re-run. */
    @Column(name = "duplicates", nullable = false)
    @Builder.Default
    private int duplicates = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
