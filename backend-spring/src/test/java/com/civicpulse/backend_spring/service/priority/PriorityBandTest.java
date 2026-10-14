package com.civicpulse.backend_spring.service.priority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounds are now what both the band label and the ?priorityBand= filter
 * use, so they must tile the score axis with no gap and no overlap.
 */
class PriorityBandTest {

    @Test
    @DisplayName("bands are contiguous: each lower bound is the previous upper bound")
    void contiguous() {
        PriorityBand[] bands = PriorityBand.values();
        assertThat(bands[0].lowerBound()).isEqualTo(Double.NEGATIVE_INFINITY);
        for (int i = 1; i < bands.length; i++) {
            assertThat(bands[i].lowerBound()).isEqualTo(bands[i - 1].upperBound());
        }
        assertThat(bands[bands.length - 1].upperBound()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("of() agrees with the bounds at every edge")
    void ofAgreesWithBounds() {
        for (PriorityBand band : PriorityBand.values()) {
            if (Double.isFinite(band.lowerBound())) {
                assertThat(PriorityBand.of(band.lowerBound())).isEqualTo(band);
                assertThat(PriorityBand.of(Math.nextDown(band.lowerBound()))).isNotEqualTo(band);
            }
        }
    }

    @Test
    @DisplayName("an unparseable score is LOW, never CRITICAL")
    void nanIsLow() {
        assertThat(PriorityBand.of(Double.NaN)).isEqualTo(PriorityBand.LOW);
    }
}
