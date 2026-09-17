package com.civicpulse.backend_spring.controller;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.dto.ingest.IngestionReport;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.service.ingest.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Bulk ingestion of externally-sourced complaints.
 *
 * <p>Under {@code /internal} because it is service-to-service, not a browser
 * endpoint: it is guarded by the shared-secret {@code X-Internal-Token} header,
 * which fails closed when the secret is unset. It holds no user session and is
 * not role-gated, for the same reason the rest of {@code /internal/*} is not.
 *
 * <p><b>It always returns 200 with a report, even when every record was
 * rejected.</b> That is deliberate. The batch was received, examined and
 * recorded — which is a successful ingestion run that found bad data, not a
 * failed request. A 4xx here would tell an operator's scheduler to retry, and
 * retrying a file full of malformed rows produces the same malformed rows. The
 * counts and the {@code ingestion_rejections} view are how the caller learns
 * what happened.
 *
 * <p>A 400 is reserved for the request itself being unusable: no records, too
 * many, or a source name that is not a source name.
 */
@RestController
@RequestMapping("/internal/ingest")
@RequiredArgsConstructor
public class IngestionController {

    /**
     * Source names become a scoping key for category aliases, ward crosswalks
     * and the rejection report, so they are constrained to something that can
     * safely be a stable identifier rather than free text from a caller.
     */
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,62}");

    private final IngestionService ingestionService;

    @PostMapping("/{source}")
    public ResponseEntity<IngestionReport> ingest(
            @PathVariable String source,
            @RequestBody List<ExternalComplaint> records) {

        if (source == null || !SOURCE.matcher(source).matches()) {
            throw new ValidationException(
                    "source must be lowercase letters, digits, hyphen or underscore");
        }

        return ResponseEntity.ok(ingestionService.ingest(source, records));
    }
}
