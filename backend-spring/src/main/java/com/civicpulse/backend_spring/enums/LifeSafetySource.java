package com.civicpulse.backend_spring.enums;

/**
 * Who raised an incident's life-safety flag (V24).
 *
 * <ul>
 *   <li>{@code REPORT_TEXT} — LifeSafetyDetector found a hazard in a member's
 *       text. A suggestion, re-evaluated on every recompute.</li>
 *   <li>{@code OFFICER} — an officer set or cleared it. Final: the detector no
 *       longer touches the flag.</li>
 * </ul>
 */
public enum LifeSafetySource {
    REPORT_TEXT,
    OFFICER
}
