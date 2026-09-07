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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
