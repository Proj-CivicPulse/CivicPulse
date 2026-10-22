package com.civicpulse.backend_spring.service.priority;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.util.GeoDistance;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes an incident's priority score and the human-readable reasons for it.
 *
 * FORMULA (0-10, every term saturating so none can swamp the range):
 *
 *   volume = min(1, log10(1 + n) / log10(1 + 25))
 *   growth = min(1, newIn24h / 5)
 *   age    = min(1, ageDays / 30)   (14 until 2026-10-22, docs/priority-calibration-proxy.md)
 *   spread = min(1, spreadMetres / 1000)
 *
 *   score  = round(10 * (0.40*volume + 0.30*growth + 0.20*age + 0.10*spread), 1)
 *
 * Why these choices:
 *
 *   - LOG for volume. The 2nd report about a pothole is far more informative
 *     than the 40th; a linear term lets one viral street dominate the whole
 *     dashboard.
 *   - 40/30/20/10. Volume is the strongest evidence the problem is REAL.
 *     Growth is what makes it urgent NOW rather than merely large. Age is a
 *     fairness/SLA term so a small old incident eventually surfaces. Spread is
 *     the weakest and most easily coincidental — two reports 900 m apart may
 *     simply be two different potholes — so it carries the least.
 *   - Age never DECAYS the score. An ageing incident should not become less
 *     urgent; "old but low-signal" is a separate analytics question
 *     (stale-incidents, Phase 5), not a priority adjustment.
 *
 * Honest weakness, worth stating in the paper: these weights are hand-set, not
 * learned. That is exactly the rule-based-vs-naive-baseline comparison Phase 8
 * already plans to run.
 *
 * STALENESS, and what now handles it: ageDays is time-dependent, so a stored
 * score drifts even with no write. Recompute on membership change alone
 * (attach, merge, unlink) therefore let a quiet incident stop ageing — the
 * fairness term above silently not working. {@code PriorityRefreshJob} closes
 * that: it sweeps open incidents whose {@code priority_computed_at} has gone
 * stale and recomputes them. It never touches membership or the match log.
 *
 * <p>STILL OPEN, and a calibration question rather than a bug: every member
 * complaint counts toward volume, growth and spread regardless of its own
 * status. An incident with ten reports, eight of them individually resolved,
 * scores as ten. Changing that changes what the weights mean, so it belongs to
 * the Phase 8 calibration pass with real data — not to a unilateral edit here.
 */
@Service
public class PriorityService {

    /** Complaint count at which the volume term saturates. */
    private static final double VOLUME_SATURATION = 25.0;
    /** Reports in 24h at which the growth term saturates. */
    private static final double GROWTH_SATURATION_24H = 5.0;
    /** Days at which the age term saturates. */
    /**
     * Days at which the age term saturates. 30 since 2026-10-22: at 14 the term
     * was pinned at 1.0 for most open incidents (priority-evaluation.md), and a
     * blind ranking preferred 30 on both halves in every run
     * (priority-calibration-proxy.md).
     */
    private static final double AGE_SATURATION_DAYS = 30.0;
    /** Metres at which the geographic-spread term saturates. */
    private static final double SPREAD_SATURATION_METRES = 1000.0;

    private static final double WEIGHT_VOLUME = 0.40;
    private static final double WEIGHT_GROWTH = 0.30;
    private static final double WEIGHT_AGE = 0.20;
    private static final double WEIGHT_SPREAD = 0.10;

    /** The detail panel renders these as chips; more than four stops being read. */
    private static final int MAX_REASONS = 4;

    /**
     * The formula's raw inputs for one incident, as of {@code now}. Exposed for
     * the calibration export (docs/roadmap.md, Phase 8), so the ranking sheet
     * and the score are read from exactly the same numbers.
     */
    public record Inputs(int reports, long reportsLast24h, long reportsLast7d, long daysOpen,
                         double spreadMetres) {
    }

    public Inputs inputs(Incident incident, List<Complaint> complaints, LocalDateTime now) {
        return new Inputs(
                complaints.size(),
                countSince(complaints, now.minusDays(1)),
                countSince(complaints, now.minusDays(7)),
                daysOpen(incident, complaints, now),
                maxPairwiseDistanceMetres(complaints));
    }

    /**
     * Age of the PROBLEM: days since its first report — the same clock the
     * incident panel's "Age", the Explorer and time-to-resolve use
     * (data-quality-rules.md). Until 2026-10-22 this read the incident's
     * created_at, which is when the GROUPING was made: the same thing for a
     * live report, but for an imported backlog it is the import day, and the
     * panel then said "Open for 21 days" beside "Age: 19 days". Falls back to
     * created_at only for an incident with no dated member.
     */
    private static long daysOpen(Incident incident, List<Complaint> complaints, LocalDateTime now) {
        LocalDateTime first = complaints.stream()
                .map(Complaint::getReportedAt)
                .filter(t -> t != null && !t.isAfter(now))
                .min(LocalDateTime::compareTo)
                .orElse(incident.getCreatedAt());
        return first == null ? 0 : Math.max(0, Duration.between(first, now).toDays());
    }

    public PriorityResult compute(Incident incident, List<Complaint> complaints, LocalDateTime now) {
        Inputs in = inputs(incident, complaints, now);
        int total = in.reports();
        long newIn24h = in.reportsLast24h();
        long newIn7d = in.reportsLast7d();
        long ageDays = in.daysOpen();
        double spreadMetres = in.spreadMetres();

        double volume = saturate(Math.log10(1 + total) / Math.log10(1 + VOLUME_SATURATION));
        double growth = saturate(newIn24h / GROWTH_SATURATION_24H);
        double age = saturate(ageDays / AGE_SATURATION_DAYS);
        double spread = saturate(spreadMetres / SPREAD_SATURATION_METRES);

        double raw = WEIGHT_VOLUME * volume
                + WEIGHT_GROWTH * growth
                + WEIGHT_AGE * age
                + WEIGHT_SPREAD * spread;

        double score = Math.round(raw * 10 * 10) / 10.0;

        List<String> reasons = buildReasons(
                total, newIn24h, newIn7d, ageDays, spreadMetres,
                volume, growth, age, spread);

        return new PriorityResult(score, PriorityBand.of(score), reasons);
    }

    /**
     * Evidence, never weights and never codes. An officer reads these to
     * understand the ranking; a number like "volume factor 0.79" explains
     * nothing to them.
     *
     * Ordered by how much each term actually contributed, so the strongest
     * evidence leads.
     */
    private List<String> buildReasons(
            int total, long newIn24h, long newIn7d, long ageDays, double spreadMetres,
            double volume, double growth, double age, double spread) {

        // A brand-new single report has no contributing factors at all. Saying
        // so keeps the explainability claim honest — an empty chip row would
        // read as "we cannot explain this", which is not what is happening.
        if (total <= 1) {
            return List.of("Single report — awaiting corroboration");
        }

        List<Scored> scored = new ArrayList<>();

        scored.add(new Scored(
                total + " complaints reported",
                WEIGHT_VOLUME * volume));

        if (newIn24h >= 1) {
            scored.add(new Scored(
                    newIn24h + (newIn24h == 1 ? " new complaint" : " new complaints")
                            + " in the last 24 hours",
                    WEIGHT_GROWTH * growth));
        }

        // Only worth saying when it is not simply restating the total.
        if (newIn7d >= 3 && newIn7d < total) {
            scored.add(new Scored(
                    newIn7d + " complaints in the last 7 days",
                    WEIGHT_GROWTH * growth * 0.5));
        }

        if (ageDays >= AGE_SATURATION_DAYS) {
            scored.add(new Scored(
                    "Open for " + ageDays + " days without resolution",
                    WEIGHT_AGE * age));
        } else if (ageDays >= 3) {
            scored.add(new Scored(
                    "Open for " + ageDays + " days",
                    WEIGHT_AGE * age));
        }

        if (spreadMetres >= 250) {
            scored.add(new Scored(
                    "Spread across roughly " + formatDistance(spreadMetres),
                    WEIGHT_SPREAD * spread));
        } else if (spreadMetres < 100 && total >= 3) {
            // Tight clustering is corroboration, not spread — it raises
            // confidence that these reports are one problem.
            scored.add(new Scored(
                    "Reports concentrated within " + formatDistance(spreadMetres),
                    WEIGHT_SPREAD * 0.5));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::contribution).reversed())
                .limit(MAX_REASONS)
                .map(Scored::text)
                .toList();
    }

    /**
     * Widest separation between any two reports in the group — the honest
     * measure of "how far does this problem stretch".
     *
     * O(n^2), which is fine: an incident is tens of complaints, not thousands.
     * If that ever changes, a bounding box is the cheap approximation.
     */
    private static double maxPairwiseDistanceMetres(List<Complaint> complaints) {
        double max = 0;
        for (int i = 0; i < complaints.size(); i++) {
            Complaint a = complaints.get(i);
            if (a.getLatitude() == null || a.getLongitude() == null) {
                continue;
            }
            for (int j = i + 1; j < complaints.size(); j++) {
                Complaint b = complaints.get(j);
                if (b.getLatitude() == null || b.getLongitude() == null) {
                    continue;
                }
                max = Math.max(max, GeoDistance.metresBetween(
                        a.getLatitude(), a.getLongitude(),
                        b.getLatitude(), b.getLongitude()));
            }
        }
        return max;
    }

    /**
     * Members REPORTED since a given instant.
     *
     * <p>By {@code reportedAt}, never {@code createdAt}. For imported complaints
     * those differ by however long the upstream backlog was, and counting by row
     * creation would score a six-month backlog imported this morning as six
     * months of reports arriving in one day — a maximal growth term on every
     * incident it formed.
     */
    private static long countSince(List<Complaint> complaints, LocalDateTime since) {
        return complaints.stream()
                .filter(c -> c.getReportedAt() != null && c.getReportedAt().isAfter(since))
                .count();
    }

    private static double saturate(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(1.0, value);
    }

    /** Rounded to something a person would say: "50 m", "800 m", "1.2 km". */
    private static String formatDistance(double metres) {
        if (metres < 1000) {
            long rounded = Math.max(10, Math.round(metres / 10.0) * 10);
            return rounded + " m";
        }
        return String.format("%.1f km", metres / 1000.0);
    }

    private record Scored(String text, double contribution) {
    }
}
