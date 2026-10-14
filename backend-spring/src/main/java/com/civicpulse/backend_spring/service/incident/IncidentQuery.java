package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.QueryParams;
import com.civicpulse.backend_spring.dto.Wire;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * A parsed, validated GET /incidents request (docs/api-contract.md, E1).
 *
 * Built only through {@link #parse}, so every instance has already passed the
 * contract's rules: repeatable filters de-duplicated, enums known, the report
 * window ordered, the page bounded. The service receives no raw strings.
 *
 * {@code categories} are as the caller sent them; the service canonicalises
 * them through the registry, which this class has no access to.
 */
public record IncidentQuery(
        List<Long> wardIds,
        List<String> categories,
        List<IncidentStatus> statuses,
        List<PriorityBand> bands,
        Double minPriority,
        LocalDateTime from,
        LocalDateTime to,
        String text,
        Sort sort,
        int limit,
        int offset) {

    /** The sortable fields, by wire name. Anything else is a 400. */
    public enum SortField {
        PRIORITY("priority", "priorityScore"),
        LAST_REPORTED_AT("lastReportedAt", "lastReportedAt"),
        FIRST_REPORTED_AT("firstReportedAt", "firstReportedAt"),
        COMPLAINT_COUNT("complaintCount", "complaintCount"),
        RESOLVED_AT("resolvedAt", "resolvedAt");

        private final String wireName;
        private final String property;

        SortField(String wireName, String property) {
            this.wireName = wireName;
            this.property = property;
        }

        static SortField parse(String value) {
            if (value == null || value.isBlank()) {
                return PRIORITY;
            }
            for (SortField field : values()) {
                if (field.wireName.equals(value.trim())) {
                    return field;
                }
            }
            throw new ValidationException("sort must be one of priority, lastReportedAt, "
                    + "firstReportedAt, complaintCount, resolvedAt");
        }

        /**
         * Priority is the queue's purpose, so it breaks its own ties on recency
         * before falling back to id. Every sort ends on id so a page boundary
         * never splits or repeats rows between requests.
         */
        Sort toSort(Sort.Direction direction) {
            Sort primary = Sort.by(direction, property);
            if (this == PRIORITY) {
                primary = primary.and(Sort.by(Sort.Direction.DESC, LAST_REPORTED_AT.property));
            }
            return primary.and(Sort.by(Sort.Direction.ASC, "id"));
        }
    }

    public static IncidentQuery parse(
            List<Long> wardIds,
            List<String> categories,
            List<String> statuses,
            List<String> priorityBands,
            Double minPriority,
            String from,
            String to,
            String q,
            String sort,
            String dir,
            Integer limit,
            Integer offset) {

        if (minPriority != null && (minPriority < 0 || minPriority > 10)) {
            throw new ValidationException("minPriority must be between 0 and 10");
        }

        LocalDateTime fromTime = QueryParams.instant(from, "from");
        LocalDateTime toTime = QueryParams.instant(to, "to");
        QueryParams.requireOrderedWindow(fromTime, toTime);

        return new IncidentQuery(
                wardIds == null ? List.of() : wardIds.stream().distinct().toList(),
                QueryParams.each(categories, value -> value),
                QueryParams.each(statuses, value -> Wire.parseEnum(IncidentStatus.class, value, "status")),
                QueryParams.each(priorityBands, IncidentQuery::parseBand),
                minPriority,
                fromTime,
                toTime,
                QueryParams.text(q),
                SortField.parse(sort).toSort(QueryParams.direction(dir, Sort.Direction.DESC)),
                QueryParams.limit(limit),
                QueryParams.offset(offset));
    }

    private static PriorityBand parseBand(String value) {
        try {
            return PriorityBand.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("priorityBand must be one of low, medium, high, critical");
        }
    }
}
