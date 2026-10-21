package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * How officers answered the decision-support recommendations
 * (GET /dashboard/recommendations, docs/roadmap.md Phase 11.3). The override
 * rate per action is the measure of whether a rule helps; null until there is
 * at least one decision.
 */
@Getter
@AllArgsConstructor
public class RecommendationSummaryDto {

    private final List<ActionRow> actions;
    private final long accepted;
    private final long overridden;
    private final Double overrideRate;

    @Getter
    @AllArgsConstructor
    public static class ActionRow {
        private final String action;
        private final long accepted;
        private final long overridden;
        private final Double overrideRate;
    }
}
