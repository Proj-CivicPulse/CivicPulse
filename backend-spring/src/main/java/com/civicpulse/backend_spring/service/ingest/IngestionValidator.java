package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.repository.WardRepository;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.ward.WardResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns one external record into either something safe to persist, or a list of
 * reasons it is not.
 *
 * <p>It never throws on bad input and never writes anything. A record with six
 * problems produces six reasons in one pass, because a gate that stops at the
 * first fault makes fixing a feed a six-round-trip conversation.
 *
 * <p>Every normalisation here reuses the machinery the public submit path
 * already uses — {@link CategoryService} for categories, {@link WardResolver}
 * for geography. A second, ingestion-only implementation of either would be a
 * second set of rules to keep in step, and they would drift.
 */
@Service
@RequiredArgsConstructor
public class IngestionValidator {

    private static final int MAX_DESCRIPTION = 5000;
    private static final int MAX_TITLE = 255;
    private static final int MAX_SOURCE_RECORD_ID = 128;
    private static final int MAX_PHOTO_URL = 1024;

    /**
     * Timestamp formats accepted, in order.
     *
     * <p>Deliberately a closed list. Guessing a format is how 03/04/2026 becomes
     * March in one record and April in the next — a silent, unfixable corruption
     * of the age term in every priority score derived from it. A feed using
     * something else gets an UNPARSEABLE reason naming the value, and somebody
     * adds the format on purpose.
     */
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final CategoryService categoryService;
    private final WardResolver wardResolver;
    private final WardRepository wardRepository;

    /** A record that passed, with everything already canonical. */
    public record Validated(
            String sourceRecordId,
            String title,
            String description,
            Category category,
            String rawCategory,
            Ward ward,
            double latitude,
            double longitude,
            LocalDateTime reportedAt,
            ComplaintStatus status,
            String photoUrl) {
    }

    public record Result(Validated validated, List<RejectionReason> reasons) {
        public boolean rejected() {
            return validated == null;
        }
    }

    public Result validate(String source, ExternalComplaint input, LocalDateTime now) {
        List<RejectionReason> reasons = new ArrayList<>();

        String sourceRecordId = trimToNull(input.getSourceRecordId());
        if (sourceRecordId == null) {
            // Fatal in a way the others are not: without a stable identifier
            // there is no way to tell a re-delivery from a new report, so
            // accepting it would guarantee duplicates on the next run.
            reasons.add(RejectionReason.missing("sourceRecordId"));
        } else if (sourceRecordId.length() > MAX_SOURCE_RECORD_ID) {
            reasons.add(RejectionReason.tooLong(
                    "sourceRecordId", sourceRecordId.length(), MAX_SOURCE_RECORD_ID));
        }

        String description = trimToNull(input.getDescription());
        if (description == null) {
            reasons.add(RejectionReason.missing("description"));
        } else if (description.length() > MAX_DESCRIPTION) {
            reasons.add(RejectionReason.tooLong("description", description.length(), MAX_DESCRIPTION));
        }

        String title = trimToNull(input.getTitle());
        if (title != null && title.length() > MAX_TITLE) {
            // Truncating silently would put a half-sentence in front of an
            // officer and call it the report's title.
            reasons.add(RejectionReason.tooLong("title", title.length(), MAX_TITLE));
        }

        String photoUrl = trimToNull(input.getPhotoUrl());
        if (photoUrl != null && photoUrl.length() > MAX_PHOTO_URL) {
            reasons.add(RejectionReason.tooLong("photoUrl", photoUrl.length(), MAX_PHOTO_URL));
        }

        Category category = null;
        String rawCategory = trimToNull(input.getCategory());
        if (rawCategory == null) {
            reasons.add(RejectionReason.missing("category"));
        } else {
            // Scoped to this feed's vocabulary first, so a term that means
            // something different upstream cannot borrow our meaning; falls back
            // to the internal vocabulary, which is where the shared spellings
            // live.
            Optional<Category> resolved = categoryService.resolve(rawCategory, source)
                    .or(() -> categoryService.resolve(rawCategory));
            if (resolved.isEmpty()) {
                reasons.add(RejectionReason.unknown("category", rawCategory));
            } else {
                category = resolved.get();
            }
        }

        Double latitude = parseCoordinate(input.getLat(), "lat", -90, 90, reasons);
        Double longitude = parseCoordinate(input.getLng(), "lng", -180, 180, reasons);

        Ward ward = null;
        String wardCode = trimToNull(input.getWardCode());
        if (wardCode != null) {
            // An explicit ward from the feed still has to exist HERE. An
            // upstream ward code we cannot map is reported, never guessed —
            // that is the whole point of the ward crosswalk.
            ward = wardRepository.findByActiveTrueOrderByIdAsc().stream()
                    .filter(w -> wardCode.equals(w.getCode())
                            || wardCode.equals(w.getSourceWardCode())
                            || wardCode.equals(w.getLgdWardCode()))
                    .findFirst()
                    .orElse(null);
            if (ward == null) {
                reasons.add(RejectionReason.unknown("wardCode", wardCode));
            }
        } else if (latitude != null && longitude != null) {
            ward = wardResolver.resolve(latitude, longitude).orElse(null);
            if (ward == null) {
                // Outside every boundary. Reported rather than pushed into the
                // nearest ward, exactly as the public submit path behaves.
                reasons.add(new RejectionReason("wardCode", "NO_WARD_AT_LOCATION",
                        latitude + ", " + longitude));
            }
        }

        LocalDateTime reportedAt = parseTimestamp(input.getReportedAt(), now, reasons);
        ComplaintStatus status = parseStatus(input.getStatus(), reasons);

        if (!reasons.isEmpty()) {
            return new Result(null, List.copyOf(reasons));
        }

        return new Result(new Validated(
                sourceRecordId, title, description, category, rawCategory,
                ward, latitude, longitude, reportedAt, status, photoUrl), List.of());
    }

    private Double parseCoordinate(
            String raw, String field, double min, double max, List<RejectionReason> reasons) {
        String value = trimToNull(raw);
        if (value == null) {
            reasons.add(RejectionReason.missing(field));
            return null;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            reasons.add(RejectionReason.unparseable(field, value));
            return null;
        }
        if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < min || parsed > max) {
            reasons.add(RejectionReason.outOfRange(field, value));
            return null;
        }
        // (0, 0) is in the Gulf of Guinea and is what a feed emits when its
        // geocoder failed. Accepting it would put a report a thousand miles out
        // to sea and, worse, cluster every such failure together.
        if (parsed == 0.0) {
            reasons.add(RejectionReason.outOfRange(field, "0 (null-island sentinel)"));
            return null;
        }
        return parsed;
    }

    private LocalDateTime parseTimestamp(
            String raw, LocalDateTime now, List<RejectionReason> reasons) {
        String value = trimToNull(raw);
        if (value == null) {
            // Absent is tolerable and means "now". A feed that does not publish
            // timestamps is still worth ingesting; one that publishes broken
            // ones is not, because the difference is invisible afterwards.
            return now;
        }

        for (DateTimeFormatter format : TIMESTAMP_FORMATS) {
            try {
                LocalDateTime parsed = format == DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        ? OffsetDateTime.parse(value, format)
                                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                        : format == DateTimeFormatter.ISO_LOCAL_DATE
                                ? java.time.LocalDate.parse(value, format).atStartOfDay()
                                : LocalDateTime.parse(value, format);

                // A report from the future is a clock or timezone bug upstream.
                // Left unchecked it would make the age term negative and the
                // growth window meaningless.
                if (parsed.isAfter(now.plusDays(1))) {
                    reasons.add(RejectionReason.outOfRange("reportedAt", value + " (in the future)"));
                    return null;
                }
                // Older than the Unix epoch is a sentinel, not a date.
                if (parsed.isBefore(LocalDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))) {
                    reasons.add(RejectionReason.outOfRange("reportedAt", value + " (before 1970)"));
                    return null;
                }
                return parsed;
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }

        reasons.add(RejectionReason.unparseable("reportedAt", value));
        return null;
    }

    private ComplaintStatus parseStatus(String raw, List<RejectionReason> reasons) {
        String value = trimToNull(raw);
        if (value == null) {
            return ComplaintStatus.OPEN;
        }

        // Normalised the same way categories are — case and separators folded —
        // so "IN PROGRESS", "in-progress" and "In_Progress" are one value.
        String normalised = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return switch (normalised) {
            case "open", "new", "registered", "pending" -> ComplaintStatus.OPEN;
            case "in_progress", "inprogress", "assigned", "work_in_progress", "wip" ->
                    ComplaintStatus.IN_PROGRESS;
            case "resolved", "completed", "done", "fixed" -> ComplaintStatus.RESOLVED;
            case "closed", "cancelled", "rejected", "withdrawn" -> ComplaintStatus.CLOSED;
            default -> {
                reasons.add(RejectionReason.unknown("status", value));
                yield null;
            }
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
