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

import java.time.LocalDateTime;

/**
 * One accepted spelling of a {@link Category}.
 *
 * <p>{@link #alias} is stored ALREADY NORMALISED — see
 * {@link com.civicpulse.backend_spring.service.category.CategoryNormalizer}.
 * Incoming values get the same transform before lookup, which is what lets
 * resolution stay an exact-equality query rather than a fuzzy search.
 *
 * <p>{@link #source} scopes the alias to the vocabulary it came from.
 * {@code internal} is our own UI and API surface; an external feed gets its own
 * source so a word that means something different over there cannot quietly
 * take over ours.
 */
@Entity
@Table(name = "category_aliases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryAlias {

    /** The vocabulary of our own UI and public API. */
    public static final String SOURCE_INTERNAL = "internal";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "alias", nullable = false, length = 128)
    private String alias;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
