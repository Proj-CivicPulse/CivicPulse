package com.civicpulse.backend_spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One reverse-geocode result, keyed by coordinates rounded to ~11 m.
 *
 * Exists so call volume tracks distinct LOCATIONS rather than complaint count —
 * reports cluster hard around the same pothole, and the second report from that
 * street should not cost anything.
 */
@Entity
@Table(name = "geocode_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeocodeCache {

    @EmbeddedId
    private Key id;

    @Column(name = "address", length = 512)
    private String address;

    /**
     * False when the provider genuinely had no address for this point. Cached
     * as a negative result: asking again next week will not produce one either.
     */
    @Column(name = "resolved", nullable = false)
    @Builder.Default
    private boolean resolved = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {

        @Column(name = "lat_key", nullable = false, precision = 9, scale = 4)
        private BigDecimal latKey;

        @Column(name = "lon_key", nullable = false, precision = 9, scale = 4)
        private BigDecimal lonKey;

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            // compareTo, not equals: BigDecimal.equals is scale-sensitive, so
            // 12.9260 and 12.926 would miss each other and re-fetch.
            return latKey.compareTo(key.latKey) == 0 && lonKey.compareTo(key.lonKey) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(latKey.stripTrailingZeros(), lonKey.stripTrailingZeros());
        }
    }
}
