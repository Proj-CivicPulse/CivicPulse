package com.civicpulse.backend_spring.entity;

import com.civicpulse.backend_spring.enums.MatchOutcome;
import com.civicpulse.backend_spring.enums.Matcher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One matching decision, written by backend-spring in the same transaction as
 * the incident membership it describes. Append-only; never updated.
 *
 * <p>This is the dataset Phase 8 evaluates the matcher on — every semantic join
 * and create, every naive fallback, and every reconcile move. FKs are stored as
 * plain ids rather than {@code @ManyToOne} associations: nothing navigates from
 * a log row, and a write path should not have to load four entities to record
 * one line.
 */
@Entity
@Table(
        name = "incident_match_log",
        indexes = {
                @Index(name = "idx_match_log_complaint", columnList = "complaint_id"),
                @Index(name = "idx_match_log_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentMatchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "complaint_id", nullable = false)
    private Long complaintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private MatchOutcome decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "matcher", nullable = false, length = 16)
    private Matcher matcher;

    /**
     * Whether this decision started a new incident rather than joining one.
     *
     * Not derivable from {@link #decision}: {@code RECONCILED} takes precedence
     * when a complaint moves, so a reconcile into a brand-new incident and one
     * into an existing incident both record {@code RECONCILED}. That join/split
     * distinction is exactly what Phase 8 measures, hence its own column.
     */
    @Column(name = "created", nullable = false)
    @Builder.Default
    private boolean created = false;

    /** The incident the complaint ended up on. Null only transiently, never persisted null. */
    @Column(name = "chosen_incident_id")
    private Long chosenIncidentId;

    /** Set when a reconcile run moved the complaint off a naive-grouped incident. */
    @Column(name = "previous_incident_id")
    private Long previousIncidentId;

    /** The member complaint the winning score was measured against (single-linkage). */
    @Column(name = "top_sibling_complaint_id")
    private Long topSiblingComplaintId;

    /** Best cosine similarity seen — recorded even when it lost to the threshold. */
    @Column(name = "top_similarity")
    private Double topSimilarity;

    @Column(name = "candidate_count", nullable = false)
    @Builder.Default
    private Integer candidateCount = 0;

    @Column(name = "threshold")
    private Double threshold;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
