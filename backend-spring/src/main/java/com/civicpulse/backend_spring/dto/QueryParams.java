package com.civicpulse.backend_spring.dto;

import com.civicpulse.backend_spring.exception.ValidationException;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Parsing for the shared Explorer query parameters, so every endpoint that
 * accepts one gives it the same meaning (docs/api-contract.md, "Explorer query
 * conventions"). The counterpart to {@link Wire}, which owns the response side.
 *
 * Every failure is a {@link ValidationException} — 400, naming the parameter and
 * the rule, never echoing the value.
 */
public final class QueryParams {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private QueryParams() {
    }

    /**
     * Default 50. Over 200 is CLAMPED, not refused: an oversized page is a
     * client bug, and the echoed limit in the envelope tells the client what it
     * actually got. Below 1 is refused — there is no sensible reading of it.
     */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new ValidationException("limit must be at least 1");
        }
        return Math.min(requested, MAX_LIMIT);
    }

    public static int offset(Integer requested) {
        if (requested == null) {
            return 0;
        }
        if (requested < 0) {
            throw new ValidationException("offset must not be negative");
        }
        return requested;
    }

    /**
     * An ISO-8601 instant ({@code 2026-10-01T00:00:00Z}) as the UTC
     * LocalDateTime the zoneless columns hold. A bare date is refused rather
     * than assumed to mean midnight in some zone.
     */
    public static LocalDateTime instant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value.trim()), ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new ValidationException(
                    field + " must be an ISO-8601 instant, e.g. 2026-10-01T00:00:00Z");
        }
    }

    /** Rejects an empty or inverted half-open window {@code [from, to)}. */
    public static void requireOrderedWindow(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ValidationException("from must be before to");
        }
    }

    /** {@code asc} or {@code desc}; absent means the endpoint's default. */
    public static Sort.Direction direction(String value, Sort.Direction fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new ValidationException("dir must be one of asc, desc");
        };
    }

    /**
     * Parses a repeatable parameter, dropping blanks and duplicates while keeping
     * order. Spring binds both {@code ?status=a&status=b} and {@code ?status=a,b}
     * to the same list, so both spellings work.
     */
    public static <T> List<T> each(List<String> values, Function<String, T> parser) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<T> parsed = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parsed.add(parser.apply(value.trim()));
            }
        }
        return new ArrayList<>(parsed);
    }

    /** Trimmed free text, or null when blank. */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
