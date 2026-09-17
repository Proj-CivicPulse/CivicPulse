package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.dto.ingest.IngestionReport;
import com.civicpulse.backend_spring.entity.IngestionBatch;
import com.civicpulse.backend_spring.enums.IngestionOutcome;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.IngestionBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ingestion boundary: the only way external data becomes a complaint.
 *
 * <p>Order of operations, and why it is this order:
 *
 * <ol>
 *   <li><b>Persist the raw payload first</b> — before validation, before
 *       anything inspects it. The record somebody needs to look at is exactly
 *       the one that failed, and a gate that discards what it refuses leaves an
 *       operator with a count and nothing to act on.
 *   <li><b>Validate everything, collecting all reasons.</b> A record with six
 *       problems reports six, because stopping at the first turns fixing a feed
 *       into a six-round-trip conversation.
 *   <li><b>Check identity before writing.</b> Same content as last time writes
 *       nothing; changed content updates in place. That is what makes re-running
 *       yesterday's file safe rather than duplicating it.
 *   <li><b>Write, then trigger matching</b> through the same event the public
 *       submit path fires, so ingested complaints group exactly like typed ones.
 * </ol>
 *
 * <p>This class deliberately holds NO transaction. Each record commits
 * separately in {@link IngestionRecordProcessor}; a ten-thousand-row batch must
 * not be all-or-nothing, because one malformed row at position 9,000 rolling
 * back 8,999 good ones is how a feed becomes impossible to onboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    /**
     * Ceiling on one request. Not arbitrary: each record is its own
     * transaction, so a batch is a long-running loop holding a connection, and
     * a caller sending a million rows in one POST should be told to page rather
     * than discovering the timeout.
     */
    public static final int MAX_BATCH_SIZE = 10_000;

    private final IngestionBatchRepository batchRepository;
    private final IngestionRecordProcessor processor;

    public IngestionReport ingest(String source, List<ExternalComplaint> records) {
        if (records == null || records.isEmpty()) {
            throw new ValidationException("records must not be empty");
        }
        if (records.size() > MAX_BATCH_SIZE) {
            throw new ValidationException(
                    "batch of " + records.size() + " exceeds the maximum of " + MAX_BATCH_SIZE);
        }

        LocalDateTime now = LocalDateTime.now();

        IngestionBatch batch = batchRepository.save(IngestionBatch.builder()
                .source(source)
                .startedAt(now)
                .received(records.size())
                .build());

        // Identifiers seen in THIS delivery, so a feed repeating one inside a
        // single file is distinguishable from the ordinary re-send.
        Set<String> seenInBatch = new HashSet<>();
        int accepted = 0;
        int rejected = 0;
        int duplicates = 0;

        for (ExternalComplaint input : records) {
            IngestionOutcome outcome;
            try {
                outcome = processor.process(source, batch.getId(), input, seenInBatch, now);
            } catch (RuntimeException ex) {
                // A record that blows up must not take the batch with it. It is
                // recorded as rejected with the failure as its reason, which is
                // more useful than a stack trace in a log nobody reads.
                log.warn("Ingestion of record {} from {} failed: {}",
                        input.getSourceRecordId(), source, ex.toString());
                try {
                    processor.recordFailure(source, batch.getId(), input, ex);
                } catch (RuntimeException nested) {
                    log.error("Could not even record the failure for source {}", source, nested);
                }
                outcome = IngestionOutcome.REJECTED;
            }

            switch (outcome) {
                case ACCEPTED, UPDATED -> accepted++;
                case REJECTED -> rejected++;
                case UNCHANGED, DUPLICATE -> duplicates++;
            }
        }

        batch.setAccepted(accepted);
        batch.setRejected(rejected);
        batch.setDuplicates(duplicates);
        batch.setFinishedAt(LocalDateTime.now());
        batchRepository.save(batch);

        log.info("Ingestion batch {} from {}: {} received, {} accepted, {} rejected, {} unchanged",
                batch.getId(), source, records.size(), accepted, rejected, duplicates);

        return new IngestionReport(
                String.valueOf(batch.getId()), source, records.size(),
                accepted, rejected, duplicates);
    }
}
