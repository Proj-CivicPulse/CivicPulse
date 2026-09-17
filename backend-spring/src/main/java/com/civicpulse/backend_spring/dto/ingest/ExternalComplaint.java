package com.civicpulse.backend_spring.dto.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One record as an external feed sends it.
 *
 * <p><b>Every field is a String, and nothing here is annotated with a
 * constraint.</b> That is deliberate and it is the whole design of the gate.
 *
 * <p>If this class validated on bind, a feed sending {@code "lat": "twelve"}
 * would fail Jackson before the record ever reached the pipeline — and the
 * platform would have a 400 and no idea which upstream record caused it. Worse,
 * one bad row would reject an entire batch of ten thousand good ones.
 *
 * <p>So binding is maximally permissive, the raw payload is persisted verbatim
 * before anything inspects it, and every decision about whether a value is
 * usable is made by {@code IngestionValidator} — which can attribute each
 * failure to a field, a code, and the record it came from.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} for the same reason: a
 * feed that adds a column next month must not break ingestion of the columns we
 * do understand. The raw JSONB keeps the new column regardless, so nothing is
 * lost by ignoring it here.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalComplaint {

    /**
     * The feed's identifier for this record. Required in practice — without it
     * no run can be idempotent — but not enforced here, so its absence is
     * reported as a rejection reason rather than a bind failure.
     */
    private String sourceRecordId;

    private String title;

    private String description;

    /** Any spelling; the registry resolves it or the record is rejected. */
    private String category;

    /** Optional. When absent, the ward is derived from the coordinates. */
    private String wardCode;

    private String lat;

    private String lng;

    /** Any of the formats {@code IngestionValidator} accepts, or a rejection. */
    private String reportedAt;

    /** The feed's own status vocabulary; normalised, not trusted. */
    private String status;

    private String photoUrl;
}
