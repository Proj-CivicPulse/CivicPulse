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

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static PriorityBand of(double score) {
        if (score >= 7.5) {
            return CRITICAL;
        }
        if (score >= 5.5) {
            return HIGH;
        }
        if (score >= 3.0) {
            return MEDIUM;
        }
        return LOW;
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
