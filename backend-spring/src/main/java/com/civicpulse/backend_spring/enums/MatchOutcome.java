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
 *   <li>{@code MERGED} — an officer merged the complaint's incident into
 *       another; {@code previousIncidentId} is the incident that was merged.</li>
 *   <li>{@code UNLINKED} — an officer split the complaint off its incident
 *       into a new one: a labelled false merge.</li>
 * </ul>
 */
public enum MatchOutcome {
    MATCHED,
    CREATED,
    RECONCILED,
    MERGED,
    UNLINKED
}
