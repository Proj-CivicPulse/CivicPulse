package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.service.priority.PriorityBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The GET /incidents parameter rules (docs/api-contract.md, E1 and conventions). */
class IncidentQueryTest {

    private static IncidentQuery parse(List<String> statuses, List<String> bands, String from, String to,
                                       String sort, String dir, Integer limit, Integer offset) {
        return IncidentQuery.parse(null, null, statuses, bands, null, from, to, null, sort, dir, limit, offset);
    }

    private static IncidentQuery defaults() {
        return parse(null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("defaults: 50 per page from 0, priority desc then recency then id")
    void defaults_() {
        IncidentQuery q = defaults();

        assertThat(q.limit()).isEqualTo(50);
        assertThat(q.offset()).isZero();
        assertThat(q.sort()).containsExactly(
                Sort.Order.desc("priorityScore"),
                Sort.Order.desc("lastReportedAt"),
                Sort.Order.asc("id"));
        assertThat(q.wardIds()).isEmpty();
        assertThat(q.statuses()).isEmpty();
    }

    @Test
    @DisplayName("an oversized page is clamped to 200, not refused")
    void clampsLimit() {
        assertThat(parse(null, null, null, null, null, null, 1000, null).limit()).isEqualTo(200);
    }

    @Test
    @DisplayName("limit < 1 and offset < 0 are refused")
    void refusesNonsensePaging() {
        assertThatThrownBy(() -> parse(null, null, null, null, null, null, 0, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> parse(null, null, null, null, null, null, null, -1))
                .isInstanceOf(ValidationException.class).hasMessageContaining("offset");
    }

    @Test
    @DisplayName("repeatable filters parse, de-duplicate and keep order")
    void repeatableFilters() {
        IncidentQuery q = parse(List.of("open", "in_progress", "open"), List.of("high", "critical"),
                null, null, null, null, null, null);

        assertThat(q.statuses()).containsExactly(IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS);
        assertThat(q.bands()).containsExactly(PriorityBand.HIGH, PriorityBand.CRITICAL);
    }

    @Test
    @DisplayName("unknown status, band, sort or dir is a 400 naming what is allowed")
    void refusesUnknownValues() {
        assertThatThrownBy(() -> parse(List.of("pending"), null, null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> parse(null, List.of("severe"), null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("low, medium, high, critical");
        assertThatThrownBy(() -> parse(null, null, null, null, "createdAt", null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("sort");
        assertThatThrownBy(() -> parse(null, null, null, null, null, "sideways", null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("dir");
    }

    @Test
    @DisplayName("from/to are ISO instants read as UTC, and must be ordered")
    void reportWindow() {
        IncidentQuery q = parse(null, null, "2026-10-01T00:00:00Z", "2026-10-08T00:00:00Z",
                null, null, null, null);
        assertThat(q.from()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(q.to()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));

        // An offset instant is converted, not truncated: 05:30 IST is 00:00 UTC.
        assertThat(parse(null, null, "2026-10-01T05:30:00+05:30", null, null, null, null, null).from())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));

        assertThatThrownBy(() -> parse(null, null, "2026-10-01", null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> parse(null, null, "2026-10-08T00:00:00Z", "2026-10-01T00:00:00Z",
                null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("before");
    }

    @Test
    @DisplayName("an explicit sort keeps the id tie-break; dir applies to the primary field")
    void explicitSort() {
        IncidentQuery q = parse(null, null, null, null, "complaintCount", "asc", null, null);
        assertThat(q.sort()).containsExactly(Sort.Order.asc("complaintCount"), Sort.Order.asc("id"));
    }

    @Test
    @DisplayName("minPriority outside 0–10 is still refused")
    void minPriorityRange() {
        assertThatThrownBy(() -> IncidentQuery.parse(null, null, null, null, 11.0,
                null, null, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
    }
}
