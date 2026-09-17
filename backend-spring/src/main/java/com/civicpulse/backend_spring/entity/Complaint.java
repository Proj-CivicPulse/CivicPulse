package com.civicpulse.backend_spring.entity;

import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.enums.MatchingStatus;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "complaints",
        indexes = {
                @Index(name = "idx_complaints_ward_id", columnList = "ward_id"),
                @Index(name = "idx_complaints_incident_id", columnList = "incident_id"),
                @Index(name = "idx_complaints_user_id", columnList = "user_id"),
                @Index(name = "idx_complaints_status", columnList = "status"),
                @Index(name = "idx_complaints_created_at", columnList = "created_at"),
                @Index(name = "idx_complaints_matching_status",
                        columnList = "matching_status, updated_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id", nullable = false)
    @ToString.Exclude
    private Ward ward;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    @ToString.Exclude
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    @ToString.Exclude
    private Department department;

    /**
     * Human-readable receipt, e.g. "CP-2026-W17-00412". Allocated once by
     * ReferenceNumberService inside the creating transaction and never
     * rewritten — hence {@code updatable = false}.
     */
    @Column(name = "reference_no", nullable = false, updatable = false, length = 32)
    private String referenceNo;

    @Column(name = "title")
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /**
     * The CANONICAL category code, resolved through the registry on the way in
     * ({@code CategoryService.require}). Never the raw string a caller sent —
     * every filter downstream compares this with exact equality.
     */
    @Column(name = "category", nullable = false)
    private String category;

    /**
     * What the caller actually sent, before normalisation. Kept so a mapping
     * decision is auditable and reversible; null on rows predating the registry,
     * where {@code category} WAS the raw value.
     */
    @Column(name = "source_category")
    private String sourceCategory;

    /**
     * The external feed this complaint arrived from, or null for the ordinary
     * case: a person filling in the public form.
     */
    @Column(name = "source", length = 64)
    private String source;

    /**
     * The feed's own identifier for this report. Unique per source, which is
     * what makes re-ingesting the same file a no-op rather than a duplicate.
     * Kept because our id is one we invented and the source has never seen.
     */
    @Column(name = "source_record_id", length = 128)
    private String sourceRecordId;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ComplaintStatus status = ComplaintStatus.OPEN;

    /**
     * Street address for the coordinates, filled in on creation when geocoding
     * is enabled. Null is normal: geocoding is optional and best-effort.
     */
    @Column(name = "address", length = 512)
    private String address;

    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

    /**
     * Where this complaint sits in the Phase 2 matching pipeline. See
     * {@link MatchingStatus} for who owns which transition.
     *
     * <p>The {@code embedding vector(1536)} column that backend-node writes is
     * deliberately <em>not</em> mapped here: Hibernate {@code ddl-auto=validate}
     * ignores unmapped columns, and a pgvector type would need a custom
     * {@code UserType} for a value Spring never reads.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "matching_status", nullable = false)
    @Builder.Default
    private MatchingStatus matchingStatus = MatchingStatus.PENDING;

    /** When the complaint reached a terminal matching state ({@code MATCHED}/{@code DEGRADED}). */
    @Column(name = "matched_at")
    private LocalDateTime matchedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
