package com.civicpulse.backend_spring.service.priority;

import java.util.Locale;

/**
 * Coarse band over the continuous priority score.
 *
 * The thresholds live here, on the server, because they are part of the
 * prioritisation model — and docs/service-boundaries.md decision 1 puts the
 * model in this service. Three consumers need the band (the React dashboard,
 * the Leaflet marker colour, and Node's Copilot); three private copies of four
 * numbers would be three chances to disagree, and re-tuning the formula would
 * silently change what "critical" means in the UI with no backend deploy.
 *
 * The raw score ships alongside it, so clients can still sort finely.
 */
public enum PriorityBand {

    // [lowerBound, upperBound). The bounds are the ONLY copy of the thresholds:
    // of() classifies with them and the ?priorityBand= filter queries with
    // them, so the label on a row and the filter that found it cannot disagree.
    LOW(Double.NEGATIVE_INFINITY, 3.0),
    MEDIUM(3.0, 5.5),
    HIGH(5.5, 7.5),
    CRITICAL(7.5, Double.POSITIVE_INFINITY);

    private final double lowerBound;
    private final double upperBound;

    PriorityBand(double lowerBound, double upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public static PriorityBand of(double score) {
        // Highest band first, comparing with >=, so anything that fails every
        // comparison — NaN included — lands in LOW, never CRITICAL.
        PriorityBand[] bands = values();
        for (int i = bands.length - 1; i > 0; i--) {
            if (score >= bands[i].lowerBound) {
                return bands[i];
            }
        }
        return LOW;
    }

    /** Inclusive. Negative infinity for LOW. */
    public double lowerBound() {
        return lowerBound;
    }

    /** Exclusive. Positive infinity for CRITICAL. */
    public double upperBound() {
        return upperBound;
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
