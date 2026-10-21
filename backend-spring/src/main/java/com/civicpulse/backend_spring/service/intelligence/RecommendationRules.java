package com.civicpulse.backend_spring.service.intelligence;

import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.service.priority.PriorityBand;

import java.util.List;

/**
 * Decision support — docs/roadmap.md, Phase 11.1. A recommended next action
 * per active incident, from fixed rules over facts the system already shows.
 *
 * <p>Rules are tried in order and the FIRST that matches is the
 * recommendation, so each incident gets exactly one and the rule that fired is
 * always nameable. Recommendations an officer can check — never an action
 * taken on their behalf: accepting one only records the decision.
 *
 * <ol>
 *   <li><b>escalate</b> — open, high or critical, first reported
 *       {@value #ESCALATE_AFTER_DAYS}+ days ago, and work has not started.</li>
 *   <li><b>dispatch</b> — open, and high or critical, or getting worse
 *       ({@value AttentionService#WORSENING_REPORTS}+ reports in 72 h).</li>
 *   <li><b>verify_and_close</b> — in progress, and no new report for
 *       {@value #QUIET_DAYS}+ days: the fix may already be in.</li>
 *   <li><b>consolidate</b> — part of a hotspot: look at its neighbours together;
 *       one trip or one cause may cover several.</li>
 *   <li><b>monitor</b> — none of the above.</li>
 * </ol>
 */
public final class RecommendationRules {

    public static final int ESCALATE_AFTER_DAYS = 7;
    public static final int QUIET_DAYS = 14;

    private RecommendationRules() {
    }

    public enum Action { ESCALATE, DISPATCH, VERIFY_AND_CLOSE, CONSOLIDATE, MONITOR }

    /** What the rules read. Days are whole days; null when there is no report. */
    public record Facts(IncidentStatus status, double priorityScore, Long daysSinceFirstReport,
                        Long daysSinceLastReport, long reportsLast72h, boolean inHotspot) {
    }

    /** The action, the rule that produced it, and why — in the officer's terms. */
    public record Decision(Action action, String ruleId, String explanation) {
    }

    public static boolean isActive(IncidentStatus status) {
        return status == IncidentStatus.OPEN || status == IncidentStatus.IN_PROGRESS;
    }

    /** First matching rule wins. Only meaningful for an active incident. */
    public static Decision decide(Facts f) {
        PriorityBand band = PriorityBand.of(f.priorityScore());
        boolean urgent = band == PriorityBand.HIGH || band == PriorityBand.CRITICAL;
        boolean worsening = f.reportsLast72h() >= AttentionService.WORSENING_REPORTS;
        boolean open = f.status() == IncidentStatus.OPEN;
        String bandName = band.name().toLowerCase(java.util.Locale.ROOT);

        if (open && urgent && f.daysSinceFirstReport() != null && f.daysSinceFirstReport() >= ESCALATE_AFTER_DAYS) {
            return new Decision(Action.ESCALATE, "urgent_unattended",
                    "Priority is " + bandName + " and work has not started " + f.daysSinceFirstReport()
                            + " days after the first report.");
        }
        if (open && (urgent || worsening)) {
            return urgent
                    ? new Decision(Action.DISPATCH, "urgent_open",
                            "Priority is " + bandName + " and work has not started.")
                    : new Decision(Action.DISPATCH, "worsening_open",
                            f.reportsLast72h() + " new reports in the last 72 hours and work has not started.");
        }
        if (f.status() == IncidentStatus.IN_PROGRESS && f.daysSinceLastReport() != null
                && f.daysSinceLastReport() >= QUIET_DAYS) {
            return new Decision(Action.VERIFY_AND_CLOSE, "quiet_in_progress",
                    "In progress with no new report for " + f.daysSinceLastReport()
                            + " days — the fix may already be in.");
        }
        if (f.inHotspot()) {
            return new Decision(Action.CONSOLIDATE, "in_hotspot",
                    "Part of a hotspot: other active incidents of this kind are within "
                            + Math.round(HotspotService.RADIUS_METRES) + " m.");
        }
        return new Decision(Action.MONITOR, "default",
                "No rule calls for action: priority is " + bandName + " and reports are steady.");
    }

    /** The facts as short lines for the UI, so the officer can check the rule. */
    public static List<String> describe(Facts f) {
        PriorityBand band = PriorityBand.of(f.priorityScore());
        return List.of(
                "Status: " + f.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                "Priority: " + band.name().toLowerCase(java.util.Locale.ROOT)
                        + String.format(java.util.Locale.ROOT, " (%.1f)", f.priorityScore()),
                "First reported: " + days(f.daysSinceFirstReport()),
                "Last reported: " + days(f.daysSinceLastReport()),
                "Reports in the last 72 h: " + f.reportsLast72h(),
                "In a hotspot: " + (f.inHotspot() ? "yes" : "no"));
    }

    private static String days(Long d) {
        if (d == null) return "never";
        if (d == 0) return "today";
        return d == 1 ? "1 day ago" : d + " days ago";
    }
}
