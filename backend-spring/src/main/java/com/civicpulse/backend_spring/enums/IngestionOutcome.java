package com.civicpulse.backend_spring.enums;

/** What the gate decided about one incoming record. */
public enum IngestionOutcome {

    /** Passed validation and became a new complaint. */
    ACCEPTED,

    /** Already known, and the payload changed upstream: the complaint was updated. */
    UPDATED,

    /**
     * Already known and byte-identical to what we hold. Nothing was written.
     * This is what a healthy re-run of yesterday's file looks like, and the
     * reason re-running is safe rather than duplicating.
     */
    UNCHANGED,

    /**
     * The same sourceRecordId appeared twice IN ONE BATCH. Distinct from
     * UNCHANGED: that is the feed contradicting itself within a single
     * delivery, which is worth seeing separately.
     */
    DUPLICATE,

    /** Failed validation. Never written to complaints; reasons are recorded. */
    REJECTED
}
