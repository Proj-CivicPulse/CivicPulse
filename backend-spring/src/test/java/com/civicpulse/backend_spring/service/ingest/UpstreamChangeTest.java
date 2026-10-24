package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** An upstream edit to a known record: what it must do to the incident. */
class UpstreamChangeTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    private static final Ward WARD = Ward.builder().id(5L).build();
    private static final Category POTHOLE = Category.builder().code("pothole").build();

    private static Complaint before() {
        return Complaint.builder().title("Pothole").description("Deep pothole at the gate")
                .category("pothole").ward(WARD).latitude(12.97).longitude(77.59)
                .reportedAt(AT).status(ComplaintStatus.OPEN).build();
    }

    private static IngestionValidator.Validated after(String title, String description, Category category,
                                                      Ward ward, double lat, LocalDateTime reportedAt,
                                                      ComplaintStatus status) {
        return new IngestionValidator.Validated("R-1", title, description, category, "Pothole", ward,
                lat, 77.59, reportedAt, status);
    }

    @Test
    @DisplayName("a changed report time is the same report: recompute, never a new record")
    void timestamp() {
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate", POTHOLE, WARD,
                12.97, AT.minusDays(3), ComplaintStatus.OPEN))).isEqualTo(UpstreamChange.RECOMPUTE);
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate", POTHOLE, WARD,
                12.97, AT, ComplaintStatus.RESOLVED))).isEqualTo(UpstreamChange.RECOMPUTE);
    }

    @Test
    @DisplayName("location, ward, category or text changed: the grouping may be wrong, re-match")
    void rematch() {
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate", POTHOLE, WARD,
                12.98, AT, ComplaintStatus.OPEN))).isEqualTo(UpstreamChange.REMATCH);
        assertThat(UpstreamChange.of(before(), after("Pothole", "Open manhole at the gate", POTHOLE, WARD,
                12.97, AT, ComplaintStatus.OPEN))).isEqualTo(UpstreamChange.REMATCH);
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate",
                Category.builder().code("drainage").build(), WARD, 12.97, AT, ComplaintStatus.OPEN)))
                .isEqualTo(UpstreamChange.REMATCH);
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate", POTHOLE,
                Ward.builder().id(6L).build(), 12.97, AT, ComplaintStatus.OPEN)))
                .isEqualTo(UpstreamChange.REMATCH);
    }

    @Test
    @DisplayName("nothing the incident reads changed: leave it alone")
    void none() {
        assertThat(UpstreamChange.of(before(), after("Pothole", "Deep pothole at the gate", POTHOLE, WARD,
                12.97, AT, ComplaintStatus.OPEN))).isEqualTo(UpstreamChange.NONE);
    }
}
