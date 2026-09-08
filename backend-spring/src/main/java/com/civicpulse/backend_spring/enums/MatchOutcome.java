package com.civicpulse.backend_spring.enums;

/**
 * What an {@code incident_match_log} row records.
 *
 * <ul>
 *   <li>{@code MATCHED} — the complaint joined an existing incident.</li>
 *   <li>{@code CREATED} — the complaint started a new incident.</li>
 *   <li>{@code RECONCILED} — a reconcile run moved the complaint off the incident
 *       a naive fallback had put it on. A labelled disagreement between the naive
 *       baseline and the real pipeline, which Phase 8 wants.</li>
 * </ul>
 */
public enum MatchOutcome {
    MATCHED,
    CREATED,
    RECONCILED
}
