package com.civicpulse.backend_spring.enums;

/**
 * Lifecycle of an incident. {@code OPEN} and {@code IN_PROGRESS} are "active".
 *
 * <p>The two terminal states are NOT synonyms, and every metric depends on the
 * difference (decided 2026-10-13, docs/data-quality-rules.md §3):
 * <ul>
 *   <li>{@link #RESOLVED} — the problem was fixed. Stamps {@code resolved_at}
 *       and counts toward every resolution metric.</li>
 *   <li>{@link #CLOSED} — terminated <em>without</em> a fix: duplicate, invalid,
 *       out of scope, not actionable. Has no resolution time and is excluded
 *       from resolution metrics. Moving {@code RESOLVED → CLOSED} therefore
 *       clears {@code resolved_at}: it is a correction that the fix never
 *       happened.</li>
 * </ul>
 */
public enum IncidentStatus {
    OPEN,
    IN_PROGRESS,
    /** Fixed. */
    RESOLVED,
    /** Terminated without a fix. Not a resolution. */
    CLOSED
}
