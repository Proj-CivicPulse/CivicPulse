package com.civicpulse.backend_spring.service.complaint;

import com.civicpulse.backend_spring.entity.Ward;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Allocates the resident-facing complaint reference number,
 * e.g. {@code CP-2026-W17-00412}.
 *
 * This is the only handle a resident has on their report, so it must be
 * unique, stable, and gap-free.
 *
 * RACE SAFETY. The counter is bumped by a single atomic upsert. Under READ
 * COMMITTED a second concurrent inserter blocks on the conflicting row lock,
 * then re-reads and increments — correct without escalating to SERIALIZABLE,
 * and contention is scoped to one (ward, year) so different wards never
 * contend with each other.
 *
 * Deliberately NOT a Postgres sequence: sequences are non-transactional, so a
 * rolled-back submission would burn a number permanently and residents would
 * see gaps in what is presented to them as a register. Because this increment
 * lives inside the complaint's own transaction, a rollback takes the number
 * back with it.
 *
 * uq_complaints_reference_no is the backstop — any future bug here becomes a
 * loud constraint violation rather than two people quietly sharing a receipt.
 */
@Service
@RequiredArgsConstructor
public class ReferenceNumberService {

    private static final String BUMP_COUNTER = """
            INSERT INTO complaint_reference_counters (ward_id, year, next_seq)
            VALUES (?, ?, 1)
            ON CONFLICT (ward_id, year)
            DO UPDATE SET next_seq = complaint_reference_counters.next_seq + 1
            RETURNING next_seq
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * MANDATORY: this must run inside the caller's transaction. Allocating in
     * its own would defeat the rollback guarantee above and reintroduce gaps.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocate(Ward ward) {
        // Matches created_at, which is UTC because the JVM default zone is
        // pinned at startup. A submission at 23:59:59.999 on 31 December could
        // in principle straddle the boundary; the consequence is one report
        // filed under the previous year, which is immaterial.
        int year = LocalDate.now(ZoneOffset.UTC).getYear();

        Long sequence = jdbcTemplate.queryForObject(
                BUMP_COUNTER, Long.class, ward.getId(), year);

        if (sequence == null) {
            throw new IllegalStateException(
                    "Reference counter returned no sequence for ward " + ward.getId());
        }

        // %05d widens rather than truncates past 99,999, which is why the
        // column is VARCHAR(32) and not CHAR(19).
        return String.format("CP-%d-W%s-%05d", year, ward.getCode(), sequence);
    }
}
