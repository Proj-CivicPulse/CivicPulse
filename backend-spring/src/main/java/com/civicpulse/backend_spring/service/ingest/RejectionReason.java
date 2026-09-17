package com.civicpulse.backend_spring.service.ingest;

/**
 * Why one field of one record was refused.
 *
 * <p>Three parts, because a rejection has to serve three different readers: the
 * {@code field} lets an operator group failures and see that a feed's whole
 * timestamp column is wrong rather than one row; the {@code code} is stable and
 * machine-readable, so the {@code ingestion_rejections} view can aggregate
 * across months without depending on wording; and {@code detail} carries the
 * offending value so somebody can take it upstream without querying the raw
 * payload.
 *
 * <p>A free-text log line would have served none of them.
 */
public record RejectionReason(String field, String code, String detail) {

    public static RejectionReason missing(String field) {
        return new RejectionReason(field, "MISSING", "value is absent or blank");
    }

    public static RejectionReason unparseable(String field, String value) {
        return new RejectionReason(field, "UNPARSEABLE", truncate(value));
    }

    public static RejectionReason outOfRange(String field, String value) {
        return new RejectionReason(field, "OUT_OF_RANGE", truncate(value));
    }

    public static RejectionReason unknown(String field, String value) {
        return new RejectionReason(field, "UNKNOWN_VALUE", truncate(value));
    }

    public static RejectionReason tooLong(String field, int length, int max) {
        return new RejectionReason(field, "TOO_LONG", length + " characters, max " + max);
    }

    /** Bounded so a feed sending a megabyte in one field cannot bloat the report. */
    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }
}
