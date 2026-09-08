package com.civicpulse.backend_spring.enums;

/**
 * Where a complaint sits in the incident-matching pipeline (Phase 2).
 *
 * <p>Ownership of the transitions is split deliberately — see
 * docs/service-boundaries.md:
 * <ul>
 *   <li>{@code PENDING <-> PROCESSING} — backend-node, via an atomic compare-and-swap
 *       claim at the start of {@code POST /complaints/{id}/process}. The claim is the
 *       single guard against two Node runs racing to attach the same complaint.</li>
 *   <li>{@code MATCHED} / {@code DEGRADED} — backend-spring only, written in the same
 *       transaction as the incident membership.</li>
 * </ul>
 */
public enum MatchingStatus {

    /** Created and not yet processed, or released after a failed Node run. */
    PENDING,

    /** A backend-node run holds the claim; the claim timestamp is {@code updated_at}. */
    PROCESSING,

    /** The semantic pipeline assigned it — to a new <em>or</em> an existing incident. */
    MATCHED,

    /** The naive fallback assigned it; {@code MatchReconciliationJob} will retry it. */
    DEGRADED
}
