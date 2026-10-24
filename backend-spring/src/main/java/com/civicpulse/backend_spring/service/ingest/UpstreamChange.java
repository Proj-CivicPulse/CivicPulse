package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.entity.Complaint;

import java.util.Objects;

/**
 * What an upstream edit to an already-imported record means for its incident.
 *
 * <p><b>Decided 2026-10-25 (roadmap: "first real data feed"):</b> a record's
 * identity is {@code (source, source_record_id)}, never its content. A
 * changed source timestamp is the feed CORRECTING when the same report was
 * made — not a new report — so it updates the complaint in place (outcome
 * {@code UPDATED}). Treating it as new would duplicate the report and count
 * one resident twice in every volume and growth figure.
 *
 * <p>What an update must then do to the incident depends on what changed:
 * <ul>
 *   <li>{@link #REMATCH} — the location, ward, category, title or description:
 *       the grouping itself may now be wrong, so the stored embedding is
 *       dropped and the complaint goes back through matching (which moves it
 *       if needed and recomputes both incidents).</li>
 *   <li>{@link #RECOMPUTE} — only the report time (or status): the
 *       grouping stands, but the incident's report span, age and growth, and
 *       so its priority, must be re-derived.</li>
 *   <li>{@link #NONE} — nothing the incident reads.</li>
 * </ul>
 */
public enum UpstreamChange {
    NONE,
    RECOMPUTE,
    REMATCH;

    static UpstreamChange of(Complaint before, IngestionValidator.Validated after) {
        boolean grouping = !Objects.equals(before.getCategory(), after.category().getCode())
                || !Objects.equals(before.getWard() == null ? null : before.getWard().getId(),
                        after.ward() == null ? null : after.ward().getId())
                || !Objects.equals(before.getLatitude(), after.latitude())
                || !Objects.equals(before.getLongitude(), after.longitude())
                || !Objects.equals(before.getDescription(), after.description())
                || !Objects.equals(before.getTitle(), after.title());
        if (grouping) return REMATCH;
        boolean derived = !Objects.equals(before.getReportedAt(), after.reportedAt())
                || before.getStatus() != after.status();
        return derived ? RECOMPUTE : NONE;
    }
}
