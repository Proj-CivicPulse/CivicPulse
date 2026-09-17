package com.civicpulse.backend_spring.dto.ingest;

/**
 * What one ingestion run did — the metrics the audit asks to be visible.
 *
 * <p>Returned to the caller AND persisted on the batch row, so an operator can
 * answer "what happened last night" without the caller having kept the
 * response. {@code batchId} is the handle for the detail: the rejected records
 * and their reasons live in {@code ingestion_records}, summarised by the
 * {@code ingestion_rejections} view.
 *
 * <p>{@code unchanged} is the number worth watching. On a daily re-delivery it
 * should be most of the batch — that is idempotency working. If it drops to
 * zero, the feed has started re-issuing identifiers and every run is about to
 * create duplicates.
 */
public record IngestionReport(
        String batchId,
        String source,
        int received,
        int accepted,
        int rejected,
        int unchanged) {
}
