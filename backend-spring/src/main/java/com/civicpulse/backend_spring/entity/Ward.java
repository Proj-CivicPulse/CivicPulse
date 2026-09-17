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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Stable ward number used in complaint reference numbers (the "17" in
     * CP-2026-W17-00412). Independent of {@code name}, which is a mutable
     * display label — a printed reference must not change when a ward is
     * renamed.
     */
    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "zone")
    private String zone;

    /**
     * Ward centroid. Nullable: a ward imported from city data may arrive
     * without coordinates, and WardResolver skips those rather than the
     * schema refusing to store the ward at all.
     */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    /**
     * Retired wards stay readable — complaints still reference them — but drop
     * out of pickers and can never be resolved into. The four V2 placeholders
     * are retired this way rather than deleted, because deleting them would
     * break foreign keys from complaints already issued a reference number.
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Which dataset this ward came from, e.g. {@code kgis-bbmp-2022}. Null only
     * on rows predating provenance tracking.
     */
    @Column(name = "source", length = 64)
    private String source;

    /** The source's own internal ward id (KGISWardID). */
    @Column(name = "source_ward_id", length = 32)
    private String sourceWardId;

    /** The source's published ward code (KGISWardCode). Unique within a source. */
    @Column(name = "source_ward_code", length = 32)
    private String sourceWardCode;

    /**
     * National Local Government Directory code. Nullable and NOT unique: 45 of
     * the 243 BBMP wards have none at all in the source — they were created by
     * the 2022 delimitation and the LGD has not issued codes for them. Absence
     * is recorded rather than invented; {@code ward_external_ids} reports which.
     */
    @Column(name = "lgd_ward_code", length = 32)
    private String lgdWardCode;

    /** When this delimitation took effect. */
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /** The dataset revision, so a future delimitation is a new version, not an edit. */
    @Column(name = "dataset_version", length = 32)
    private String datasetVersion;

    // NOTE: `boundary geometry(MultiPolygon, 4326)` is deliberately NOT mapped.
    // Nothing in Java needs the polygon itself — only the question "does this
    // point fall inside it", which PostGIS answers far better than we could in
    // application code. Leaving it unmapped keeps hibernate-spatial and its
    // JTS dependency out of the build entirely. Hibernate's ddl-auto=validate
    // checks that mapped columns exist, not that every column is mapped, so an
    // unmapped column is not drift.

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
