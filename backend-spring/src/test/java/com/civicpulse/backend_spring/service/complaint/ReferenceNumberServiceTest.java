package com.civicpulse.backend_spring.service.complaint;

import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.repository.WardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for reference-number allocation. Needs a live database, the
 * same as BackendSpringApplicationTests.
 *
 * Named *Test, not *IT, on purpose: no failsafe plugin is configured, so an
 * *IT class would be silently skipped by both `mvn test` and `mvn verify` and
 * this would look like passing coverage while never executing. The existing
 * BackendSpringApplicationTests sets the same precedent.
 *
 * @Transactional means everything here rolls back, so the test does not leave
 * gaps in the real per-ward sequence — and the rollback itself demonstrates the
 * property that motivated a counter table over a Postgres sequence: a
 * rolled-back submission gives its number back rather than burning it.
 *
 * Concurrency is deliberately NOT tested with threads. A racing test against a
 * shared Neon branch is flaky, and the real guarantee is the pair of
 * uq_complaints_reference_no plus the atomic upsert — not a test that happens
 * to interleave the right way on one run.
 */
@SpringBootTest
@Transactional
class ReferenceNumberServiceTest {

    private static final Pattern FORMAT = Pattern.compile("^CP-\\d{4}-W[A-Za-z0-9]+-\\d{5,}$");

    @Autowired
    private ReferenceNumberService referenceNumberService;

    @Autowired
    private WardRepository wardRepository;

    private Ward ward;

    @BeforeEach
    void setUp() {
        List<Ward> wards = wardRepository.findAllByOrderByIdAsc();
        assumeTrue(!wards.isEmpty(), "reference data must be seeded");
        ward = wards.getFirst();
    }

    @Test
    @DisplayName("allocates the documented format, using the ward code rather than its id")
    void formatUsesWardCode() {
        String reference = referenceNumberService.allocate(ward);

        assertThat(reference).matches(FORMAT);
        assertThat(reference).startsWith(
                "CP-" + LocalDate.now(ZoneOffset.UTC).getYear() + "-W" + ward.getCode() + "-");
    }

    @Test
    @DisplayName("200 allocations are unique, sequential, and gap-free")
    void sequentialAndUnique() {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            references.add(referenceNumberService.allocate(ward));
        }

        assertThat(references).doesNotHaveDuplicates();
        assertThat(references).allMatch(reference -> FORMAT.matcher(reference).matches());

        // Consecutive with no holes: the sequence is what makes the 5 digits
        // mean "the Nth report in this ward this year".
        List<Long> sequences = references.stream().map(ReferenceNumberServiceTest::sequenceOf).toList();
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(sequences.get(i - 1) + 1);
        }
    }

    @Test
    @DisplayName("separate wards keep separate counters")
    void perWardCounters() {
        List<Ward> wards = wardRepository.findAllByOrderByIdAsc();
        assumeTrue(wards.size() >= 2, "needs at least two seeded wards");

        Ward first = wards.get(0);
        Ward second = wards.get(1);

        long firstBefore = sequenceOf(referenceNumberService.allocate(first));
        // Bumping a different ward must not advance the first ward's counter.
        referenceNumberService.allocate(second);
        referenceNumberService.allocate(second);
        long firstAfter = sequenceOf(referenceNumberService.allocate(first));

        assertThat(firstAfter).isEqualTo(firstBefore + 1);
    }

    private static long sequenceOf(String reference) {
        return Long.parseLong(reference.substring(reference.lastIndexOf('-') + 1));
    }
}
