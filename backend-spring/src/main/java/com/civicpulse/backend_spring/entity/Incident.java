package com.civicpulse.backend_spring.entity;

import com.civicpulse.backend_spring.enums.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "incidents",
        indexes = {
                @Index(name = "idx_incidents_matching", columnList = "ward_id, category, status"),
                @Index(name = "idx_incidents_status", columnList = "status"),
                @Index(name = "idx_incidents_priority_score", columnList = "priority_score"),
                @Index(name = "idx_incidents_priority_computed_at",
                        columnList = "status, priority_computed_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id", nullable = false)
    @ToString.Exclude
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    @ToString.Exclude
    private Department department;

    @Column(name = "title")
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /**
     * Canonical category code, inherited from the complaint that opened the
     * incident — which was itself normalised through the registry.
     */
    @Column(name = "category", nullable = false)
    private String category;

    /** The opening complaint's raw category, carried through for audit. */
    @Column(name = "source_category")
    private String sourceCategory;

    @Column(name = "priority_score", nullable = false)
    @Builder.Default
    private Double priorityScore = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "priority_reasons", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> priorityReasons = new ArrayList<>();

    /**
     * When priorityScore was last derived.
     *
     * The age term of the formula is time-dependent, so a stored score goes
     * stale with no write at all. This is what lets PriorityRefreshJob tell a
     * current score from a drifted one — {@code updatedAt} cannot, because any
     * write bumps it. Null means "never recomputed since the column existed",
     * which the sweep treats as the oldest possible.
     */
    @Column(name = "priority_computed_at")
    private LocalDateTime priorityComputedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private IncidentStatus status = IncidentStatus.OPEN;

    @Column(name = "complaint_count", nullable = false)
    @Builder.Default
    private Integer complaintCount = 1;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    /** Street address of the incident centroid. Null when geocoding is off. */
    @Column(name = "address", length = 512)
    private String address;

    @Column(name = "ai_recommendation", columnDefinition = "TEXT")
    private String aiRecommendation;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
