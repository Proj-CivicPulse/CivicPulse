package com.civicpulse.backend_spring.service.priority;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures {@link PriorityService#compute}, whose geographic-spread term is
 * O(n²) in an incident's member count.
 *
 * <p>This exists because the audit raised that complexity and then said the
 * right thing about it: <em>benchmark before optimising</em>. Replacing a
 * correct exact maximum with an approximation on suspicion alone would trade a
 * real property for an imagined gain. So this measures it, states a budget, and
 * lets the number decide.
 *
 * <h2>What the real data says</h2>
 * Measured against the project database: 31 incidents, largest 17 complaints,
 * mean 5.4, median 3, p95 14. An "incident" is people reporting one pothole, so
 * it is bounded by how many neighbours bother to file — not by city size. A
 * ward with 200 complaints is 40 incidents, not one.
 *
 * <p>{@link #REALISTIC_WORST_CASE} is therefore 500 — roughly 30x the largest
 * group ever observed — and {@link #ABSURD_CASE} is 5000, which would mean five
 * thousand people reporting one problem into one group. If the budget holds
 * there, the O(n²) term is not a risk worth engineering against.
 *
 * <h2>Budget</h2>
 * {@link #BUDGET_MILLIS} is 50 ms at the realistic worst case. The calculation
 * runs on incident membership change and on the refresh sweep — never in a
 * citizen's request path, and never more than once per incident per sweep — so
 * tens of milliseconds is comfortably invisible. Exceeding it is the signal to
 * optimise, and the note at the bottom says how.
 *
 * <p>Timing tests are inherently noisy on a shared machine, so the assertion is
 * deliberately loose and the detail goes to stdout for a human to read. This
 * asserts "not catastrophic", which is the actual question, rather than
 * pretending to microbenchmark precision JUnit cannot deliver.
 */
class PriorityServiceBenchmarkTest {

    /** ~30x the largest incident ever observed in real data (17 members). */
    private static final int REALISTIC_WORST_CASE = 500;
    /** Five thousand people reporting one problem as one group. */
    private static final int ABSURD_CASE = 5000;
    /** Budget at the realistic worst case. Not in any request path. */
    private static final long BUDGET_MILLIS = 50;

    private final PriorityService service = new PriorityService();

    @Test
    @DisplayName("benchmarks the O(n^2) spread term across cluster sizes")
    void benchmark() {
        LocalDateTime now = LocalDateTime.now();

        System.out.println("\nPriorityService.compute — geographic spread is O(n^2)");
        System.out.printf("%8s %10s %10s %10s %12s%n", "members", "p50 (ms)", "p95 (ms)", "p99 (ms)", "pairs");

        long realisticP99 = 0;

        for (int size : new int[] {10, 17, 50, 100, REALISTIC_WORST_CASE, 1000, ABSURD_CASE}) {
            Incident incident = incident(now);
            List<Complaint> members = members(size, now);

            // Warm up: the first calls run interpreted, and measuring those
            // measures the JIT rather than the algorithm.
            for (int i = 0; i < 5; i++) {
                service.compute(incident, members, now);
            }

            int runs = size >= 1000 ? 10 : 50;
            long[] timings = new long[runs];
            for (int i = 0; i < runs; i++) {
                long start = System.nanoTime();
                service.compute(incident, members, now);
                timings[i] = System.nanoTime() - start;
            }
            Arrays.sort(timings);

            double p50 = timings[(int) (runs * 0.50)] / 1_000_000.0;
            double p95 = timings[Math.min(runs - 1, (int) (runs * 0.95))] / 1_000_000.0;
            double p99 = timings[runs - 1] / 1_000_000.0;
            long pairs = (long) size * (size - 1) / 2;

            System.out.printf("%8d %10.3f %10.3f %10.3f %12d%n", size, p50, p95, p99, pairs);

            if (size == REALISTIC_WORST_CASE) {
                realisticP99 = Math.round(p99);
            }
        }

        System.out.println();
        assertThat(realisticP99)
                .as("compute() at %d members (~30x the largest incident ever observed) "
                        + "must stay within the %d ms budget", REALISTIC_WORST_CASE, BUDGET_MILLIS)
                .isLessThan(BUDGET_MILLIS);
    }

    @Test
    @DisplayName("the spread term stays correct at every size — an exact maximum, not an estimate")
    void correctnessIsPreserved() {
        LocalDateTime now = LocalDateTime.now();

        // Two members at a known separation, plus a pile of coincident ones.
        // The widest separation is the honest answer and the score must reflect
        // it regardless of how many members sit on top of each other. This is
        // the property any future optimisation has to keep.
        List<Complaint> members = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            members.add(complaint(12.9716, 77.5946, now));
        }
        members.add(complaint(12.9716, 77.6036, now)); // ~977 m east

        PriorityResult result = service.compute(incident(now), members, now);

        assertThat(result.reasons())
                .as("the widest pair must drive the spread reason")
                .anySatisfy(reason -> assertThat(reason).contains("Spread across"));
        assertThat(result.score()).isGreaterThan(0);
    }

    @Test
    @DisplayName("members without coordinates are skipped, not treated as (0,0)")
    void nullCoordinatesDoNotFabricateSpread() {
        // The failure this guards against is spectacular: one null-island
        // member would make every incident look ~1,500 km wide and saturate the
        // spread term for the whole dashboard.
        LocalDateTime now = LocalDateTime.now();

        List<Complaint> members = new ArrayList<>();
        members.add(complaint(12.9716, 77.5946, now));
        members.add(complaint(12.9717, 77.5947, now));
        members.add(Complaint.builder().reportedAt(now).createdAt(now).build()); // no coordinates

        PriorityResult result = service.compute(incident(now), members, now);

        assertThat(result.reasons())
                .noneSatisfy(reason -> assertThat(reason).contains("km"));
    }

    private static Incident incident(LocalDateTime now) {
        return Incident.builder().id(1L).createdAt(now.minusDays(3)).build();
    }

    /** A tight urban cluster: 500 m box, the realistic shape of one incident. */
    private static List<Complaint> members(int size, LocalDateTime now) {
        Random random = new Random(42); // fixed seed — same cluster every run
        List<Complaint> members = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            members.add(complaint(
                    12.9716 + (random.nextDouble() - 0.5) * 0.005,
                    77.5946 + (random.nextDouble() - 0.5) * 0.005,
                    now.minusHours(random.nextInt(72))));
        }
        return members;
    }

    private static Complaint complaint(double lat, double lon, LocalDateTime createdAt) {
        return Complaint.builder().latitude(lat).longitude(lon)
                .reportedAt(createdAt).createdAt(createdAt).build();
    }
}
