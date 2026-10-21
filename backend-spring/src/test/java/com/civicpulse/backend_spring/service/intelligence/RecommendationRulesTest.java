package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules.Action;
import com.civicpulse.backend_spring.service.intelligence.RecommendationRules.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The decision rules, case by case, including which rule wins when several match. */
class RecommendationRulesTest {

    private static final IncidentStatus OPEN = IncidentStatus.OPEN;
    private static final IncidentStatus IN_PROGRESS = IncidentStatus.IN_PROGRESS;

    private static Action action(IncidentStatus status, double score, Long first, Long last, long recent, boolean hotspot) {
        return RecommendationRules.decide(new Facts(status, score, first, last, recent, hotspot)).action();
    }

    @Test
    @DisplayName("urgent and unattended for a week → escalate, ahead of every other rule")
    void escalate() {
        assertThat(action(OPEN, 6.0, 7L, 0L, 5, true)).isEqualTo(Action.ESCALATE);
        assertThat(action(OPEN, 8.0, 30L, 2L, 0, false)).isEqualTo(Action.ESCALATE);
        assertThat(action(OPEN, 6.0, 6L, 0L, 0, false)).isEqualTo(Action.DISPATCH);     // under a week
        assertThat(action(IN_PROGRESS, 6.0, 30L, 1L, 0, false)).isEqualTo(Action.MONITOR); // work started
    }

    @Test
    @DisplayName("open and urgent, or open and getting worse → dispatch")
    void dispatch() {
        assertThat(RecommendationRules.decide(new Facts(OPEN, 5.5, 1L, 0L, 0, false)).ruleId()).isEqualTo("urgent_open");
        assertThat(RecommendationRules.decide(new Facts(OPEN, 2.0, 1L, 0L, 2, false)).ruleId()).isEqualTo("worsening_open");
        assertThat(action(OPEN, 2.0, 1L, 0L, 1, false)).isEqualTo(Action.MONITOR);        // one report is not a trend
        assertThat(action(IN_PROGRESS, 2.0, 1L, 0L, 5, false)).isEqualTo(Action.MONITOR); // already being worked
    }

    @Test
    @DisplayName("in progress and quiet for two weeks → verify and close")
    void verifyAndClose() {
        assertThat(action(IN_PROGRESS, 4.0, 40L, 14L, 0, true)).isEqualTo(Action.VERIFY_AND_CLOSE);
        assertThat(action(IN_PROGRESS, 4.0, 40L, 13L, 0, false)).isEqualTo(Action.MONITOR);
        assertThat(action(OPEN, 2.0, 40L, 20L, 0, false)).isEqualTo(Action.MONITOR);       // open, not in progress
    }

    @Test
    @DisplayName("part of a hotspot, nothing more pressing → consolidate; otherwise monitor")
    void consolidateAndMonitor() {
        assertThat(action(OPEN, 3.0, 2L, 1L, 0, true)).isEqualTo(Action.CONSOLIDATE);
        assertThat(action(OPEN, 3.0, 2L, 1L, 0, false)).isEqualTo(Action.MONITOR);
        assertThat(action(OPEN, 3.0, null, null, 0, false)).isEqualTo(Action.MONITOR);    // no reports at all
    }

    @Test
    @DisplayName("the explanation and facts are in the officer's terms")
    void explanation() {
        Facts facts = new Facts(OPEN, 6.1, 9L, 0L, 3, false);
        assertThat(RecommendationRules.decide(facts).explanation())
                .isEqualTo("Priority is high and work has not started 9 days after the first report.");
        assertThat(RecommendationRules.describe(facts)).containsExactly(
                "Status: open", "Priority: high (6.1)", "First reported: 9 days ago",
                "Last reported: today", "Reports in the last 72 h: 3", "In a hotspot: no");
    }
}
