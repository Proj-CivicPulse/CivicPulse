package com.civicpulse.backend_spring.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * The recommended next action for one incident
 * (GET /incidents/{id}/recommendation, docs/roadmap.md Phase 11).
 *
 * <p>{@code action} is null for a resolved or closed incident — there is
 * nothing to recommend, and {@code explanation} says so.
 */
@Getter
@AllArgsConstructor
public class RecommendationDto {

    private final String incidentId;
    /** escalate | dispatch | verify_and_close | consolidate | monitor; null when inactive. */
    private final String action;
    /** Which rule fired, e.g. "urgent_unattended". Null when inactive. */
    private final String ruleId;
    private final String explanation;
    /** The facts the rules read, as short lines, so the officer can check them. */
    private final List<String> facts;
    private final String asOf;
    /** The most recent officer decision on this incident, or null. */
    private final Feedback lastFeedback;

    @Getter
    @AllArgsConstructor
    public static class Feedback {
        /** accepted | overridden */
        private final String decision;
        private final String recommendedAction;
        /** Set when overridden. */
        private final String chosenAction;
        private final String note;
        private final String userId;
        private final String createdAt;
    }
}
