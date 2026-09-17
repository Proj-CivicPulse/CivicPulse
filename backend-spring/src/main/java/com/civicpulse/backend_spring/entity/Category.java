package com.civicpulse.backend_spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A canonical complaint category — the single authority for what a category
 * may be.
 *
 * <p>{@link #code} is what actually gets stored in {@code complaints.category}
 * and {@code incidents.category}, and therefore what every exact-equality
 * filter compares: the semantic matcher's candidate pre-filter in backend-node,
 * the naive fallback, the dashboard breakdown. It never changes once seeded.
 * {@code name} and {@code displayName} are labels and may be edited freely.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    /** Officer-facing canonical name. */
    @Column(name = "name", nullable = false)
    private String name;

    /** Resident-facing label on the submit form. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * Inactive categories stay readable, because complaints already reference
     * them, but drop out of the submit form and are refused on new reports.
     */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** Optional rollup. Unused at seed time; present so a hierarchy costs no migration of consumers. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
