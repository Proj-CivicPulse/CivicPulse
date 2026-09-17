package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.IngestionRecord;
import com.civicpulse.backend_spring.enums.IngestionOutcome;
import com.civicpulse.backend_spring.enums.MatchingStatus;
import com.civicpulse.backend_spring.event.ComplaintCreatedEvent;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.IngestionRecordRepository;
import com.civicpulse.backend_spring.service.complaint.ReferenceNumberService;
import com.civicpulse.backend_spring.service.geocoding.GeocodingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ingests ONE record, in ONE transaction.
 *
 * <p>This is a separate bean from {@link IngestionService} for a reason that is
 * easy to get wrong: {@code @Transactional(REQUIRES_NEW)} is implemented by a
 * proxy, and a method called from another method of the SAME bean bypasses that
 * proxy entirely. Had this stayed a private method on the service, every record
 * would have silently run inside the caller's transaction and one bad row at
 * position 9,000 would have rolled back 8,999 good ones — with nothing in the
 * code to suggest it.
 *
 * <p>So: the batch loop lives there, the transactional unit lives here, and the
 * boundary between them is a real bean boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionRecordProcessor {

    private final IngestionValidator validator;
    private final IngestionRecordRepository recordRepository;
    private final ComplaintRepository complaintRepository;
    private final ReferenceNumberService referenceNumberService;
    private final GeocodingService geocodingService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * A PRIVATE mapper with fixed configuration, deliberately NOT the
     * application's injected one, and deliberately Jackson 3.
     *
     * <p><b>Private, because its output is hashed</b> and that hash is the
     * idempotency key: it decides whether tomorrow's delivery of a record is a
     * no-op or an update. Using the app-wide mapper would mean any future
     * Jackson configuration change — a naming strategy, a null-inclusion
     * setting, a new module — silently changed every hash, and the next run of
     * every feed would see its whole back catalogue as "changed" and rewrite it.
     *
     * <p><b>Jackson 3 (tools.jackson), because that is what this application
     * actually ships.</b> Jackson 2 is on the classpath only transitively, via
     * jjwt-jackson (see SecurityErrorHandlers) — building the idempotency key of
     * an ingestion pipeline on a library nothing declares is a dependency that
     * disappears the day the JWT library changes.
     *
     * <p>Switching mappers changes the serialised form and therefore every
     * hash. That is free only while no ingestion records exist; after a feed is
     * live it would force one spurious full re-import. Do not change it again.
     */
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestionOutcome process(
            String source, Long batchId, ExternalComplaint input,
            Set<String> seenInBatch, LocalDateTime now) {

        Map<String, Object> raw = toRawMap(input);
        String contentHash = hash(raw);
        String sourceRecordId = trimToNull(input.getSourceRecordId());

        // The same id twice inside ONE delivery: the feed contradicting itself,
        // which is a different problem from re-sending yesterday's rows and is
        // worth being able to count separately.
        if (sourceRecordId != null && !seenInBatch.add(sourceRecordId)) {
            recordRepository.save(record(source, batchId, sourceRecordId, raw, contentHash,
                    IngestionOutcome.DUPLICATE, List.of(), null));
            return IngestionOutcome.DUPLICATE;
        }

        IngestionValidator.Result result = validator.validate(source, input, now);
        if (result.rejected()) {
            recordRepository.save(record(source, batchId, sourceRecordId, raw, contentHash,
                    IngestionOutcome.REJECTED, result.reasons(), null));
            return IngestionOutcome.REJECTED;
        }

        IngestionValidator.Validated valid = result.validated();
        Optional<Complaint> existing =
                complaintRepository.findBySourceAndSourceRecordId(source, valid.sourceRecordId());

        if (existing.isPresent()) {
            Optional<IngestionRecord> previous = recordRepository
                    .findFirstBySourceAndSourceRecordIdOrderByIdDesc(source, valid.sourceRecordId());

            // Byte-identical to what produced the row we already hold: write
            // NOTHING. This is the common case on a daily re-delivery, and
            // making it free is what lets a feed be re-run without thought.
            if (previous.isPresent() && contentHash.equals(previous.get().getContentHash())) {
                recordRepository.save(record(source, batchId, valid.sourceRecordId(), raw,
                        contentHash, IngestionOutcome.UNCHANGED, List.of(), existing.get().getId()));
                return IngestionOutcome.UNCHANGED;
            }

            Complaint updated = applyTo(existing.get(), valid);
            complaintRepository.save(updated);
            recordRepository.save(record(source, batchId, valid.sourceRecordId(), raw, contentHash,
                    IngestionOutcome.UPDATED, List.of(), updated.getId()));
            return IngestionOutcome.UPDATED;
        }

        Complaint complaint = applyTo(Complaint.builder()
                .source(source)
                .sourceRecordId(valid.sourceRecordId())
                .matchingStatus(MatchingStatus.PENDING)
                // Allocated inside this transaction, so a rollback returns the
                // number rather than burning it — same as the public path.
                .referenceNo(referenceNumberService.allocate(valid.ward()))
                .build(), valid);

        Complaint saved = complaintRepository.saveAndFlush(complaint);

        // created_at must carry the UPSTREAM report time, not the import moment.
        // Setting it on the entity does NOT work: @CreationTimestamp generates
        // the value on insert and discards whatever was there. So the row is
        // inserted first and corrected here, inside the same transaction.
        //
        // This is not cosmetic. Without it a six-month backlog imported on a
        // Tuesday looks like it all arrived on Tuesday: every incident gets a
        // zero age and a maximal 24-hour growth score, and the entire officer
        // queue inverts on import day.
        complaintRepository.backdateCreatedAt(saved.getId(), valid.reportedAt());
        saved.setCreatedAt(valid.reportedAt());

        // The same event the public submit path fires, so ingested complaints
        // run through the identical matching pipeline. An ingestion-only
        // grouping path would be a second matcher to keep in step with the first.
        eventPublisher.publishEvent(new ComplaintCreatedEvent(saved.getId()));

        recordRepository.save(record(source, batchId, valid.sourceRecordId(), raw, contentHash,
                IngestionOutcome.ACCEPTED, List.of(), saved.getId()));
        return IngestionOutcome.ACCEPTED;
    }

    /**
     * Records a record that blew up, in its own transaction so it survives the
     * rollback of the attempt that failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String source, Long batchId, ExternalComplaint input, RuntimeException cause) {
        Map<String, Object> raw = toRawMap(input);
        recordRepository.save(record(source, batchId, trimToNull(input.getSourceRecordId()), raw,
                hash(raw), IngestionOutcome.REJECTED,
                List.of(new RejectionReason("*", "INTERNAL_ERROR", cause.getClass().getSimpleName())),
                null));
    }

    private Complaint applyTo(Complaint complaint, IngestionValidator.Validated valid) {
        complaint.setTitle(valid.title());
        complaint.setDescription(valid.description());
        // Canonical code stored, raw spelling preserved — identical to the
        // public path, because both go through CategoryService.
        complaint.setCategory(valid.category().getCode());
        complaint.setSourceCategory(valid.rawCategory());
        complaint.setWard(valid.ward());
        complaint.setLatitude(valid.latitude());
        complaint.setLongitude(valid.longitude());
        complaint.setStatus(valid.status());
        complaint.setPhotoUrl(valid.photoUrl());
        if (complaint.getAddress() == null) {
            complaint.setAddress(geocodingService
                    .reverseGeocode(valid.latitude(), valid.longitude()).orElse(null));
        }
        return complaint;
    }

    private IngestionRecord record(
            String source, Long batchId, String sourceRecordId, Map<String, Object> raw,
            String contentHash, IngestionOutcome status, List<RejectionReason> reasons,
            Long complaintId) {

        List<Map<String, String>> encoded = new ArrayList<>(reasons.size());
        for (RejectionReason reason : reasons) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("field", reason.field());
            entry.put("code", reason.code());
            entry.put("detail", reason.detail());
            encoded.add(entry);
        }

        return IngestionRecord.builder()
                .batchId(batchId)
                .source(source)
                .sourceRecordId(sourceRecordId)
                .raw(raw)
                .contentHash(contentHash)
                .status(status)
                .rejectionReasons(encoded)
                .complaintId(complaintId)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toRawMap(ExternalComplaint input) {
        // TreeMap: key order becomes part of the serialised form the hash is
        // taken over, so sorting makes the hash depend on CONTENT rather than on
        // whatever order the feed's JSON happened to use that day.
        return new TreeMap<>(HASH_MAPPER.convertValue(input, Map.class));
    }

    private String hash(Map<String, Object> raw) {
        try {
            // Jackson 3 throws unchecked, so only the digest needs catching.
            String canonical = HASH_MAPPER.writeValueAsString(raw);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
