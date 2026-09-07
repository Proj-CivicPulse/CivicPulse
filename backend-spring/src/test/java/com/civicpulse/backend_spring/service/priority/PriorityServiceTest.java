package com.civicpulse.backend_spring.service.priority;

import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the prioritisation model: the formula, the band cut-offs, and the exact
 * reason strings.
 *
 * Pure unit test, no Spring context — the model has no dependencies beyond
 * arithmetic and it should stay that way.
 *
 * These assertions are deliberately exact. The reason strings are shown to ward
 * officers verbatim and the bands drive map colour, so a change to either
 * should have to be made on purpose, here, rather than sliding through as a
 * side effect of a refactor.
 */
class PriorityServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 12, 0);
    private static final double BASE_LAT = 12.9716;
    private static final double BASE_LON = 77.5946;

    /** ~400 m north of BASE_LAT. */
    private static final double LAT_400M = BASE_LAT + 0.003593;

    private final PriorityService service = new PriorityService();

    @Nested
    @DisplayName("score")
    class Score {

        @Test
        @DisplayName("12 reports, 3 in the last day, two weeks old, spread 400 m -> 7.3 (high)")
        void representativeIncident() {
            Incident incident = incidentCreated(NOW.minusDays(14));
            List<Complaint> complaints = new ArrayList<>();
            // 3 arrived in the last 24 hours...
            for (int i = 0; i < 3; i++) {
                complaints.add(complaint(NOW.minusHours(2), BASE_LAT, BASE_LON));
            }
            // ...and 9 older ones, one of them 400 m away.
            complaints.add(complaint(NOW.minusDays(10), LAT_400M, BASE_LON));
            for (int i = 0; i < 8; i++) {
                complaints.add(complaint(NOW.minusDays(10), BASE_LAT, BASE_LON));
            }

            PriorityResult result = service.compute(incident, complaints, NOW);

            // NOTE: docs/api-contract.md originally illustrated this shape with
            // 7.4. That figure was written by hand before any formula existed;
            // 7.3 is what the model actually produces, and the doc has been
            // corrected to match rather than the weights bent to fit it.
            assertThat(result.score()).isEqualTo(7.3);
            assertThat(result.band()).isEqualTo(PriorityBand.HIGH);
        }

        @Test
        @DisplayName("every term saturates, so the score cannot exceed 10")
        void saturates() {
            Incident incident = incidentCreated(NOW.minusDays(90));
            List<Complaint> complaints = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                complaints.add(complaint(NOW.minusHours(1), BASE_LAT, BASE_LON));
            }
            // 5 km apart — far past the 1 km spread saturation.
            complaints.add(complaint(NOW.minusHours(1), BASE_LAT + 0.045, BASE_LON));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.score()).isEqualTo(10.0);
            assertThat(result.band()).isEqualTo(PriorityBand.CRITICAL);
        }

        @Test
        @DisplayName("a lone brand-new report scores low")
        void singleReport() {
            Incident incident = incidentCreated(NOW);
            List<Complaint> complaints = List.of(complaint(NOW, BASE_LAT, BASE_LON));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.band()).isEqualTo(PriorityBand.LOW);
        }

        @Test
        @DisplayName("age never decays the score")
        void ageOnlyAdds() {
            List<Complaint> complaints = List.of(
                    complaint(NOW.minusDays(40), BASE_LAT, BASE_LON),
                    complaint(NOW.minusDays(40), BASE_LAT, BASE_LON));

            double young = service.compute(incidentCreated(NOW.minusDays(1)), complaints, NOW).score();
            double old = service.compute(incidentCreated(NOW.minusDays(60)), complaints, NOW).score();

            assertThat(old).isGreaterThan(young);
        }

        @Test
        @DisplayName("an incident with no complaints does not blow up")
        void emptyGroup() {
            PriorityResult result = service.compute(incidentCreated(NOW), List.of(), NOW);

            assertThat(result.score()).isGreaterThanOrEqualTo(0.0);
            assertThat(result.reasons()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("bands")
    class Bands {

        @Test
        @DisplayName("cut-offs are inclusive at the lower bound")
        void boundaries() {
            assertThat(PriorityBand.of(0.0)).isEqualTo(PriorityBand.LOW);
            assertThat(PriorityBand.of(2.9)).isEqualTo(PriorityBand.LOW);
            assertThat(PriorityBand.of(3.0)).isEqualTo(PriorityBand.MEDIUM);
            assertThat(PriorityBand.of(5.4)).isEqualTo(PriorityBand.MEDIUM);
            assertThat(PriorityBand.of(5.5)).isEqualTo(PriorityBand.HIGH);
            assertThat(PriorityBand.of(7.4)).isEqualTo(PriorityBand.HIGH);
            assertThat(PriorityBand.of(7.5)).isEqualTo(PriorityBand.CRITICAL);
            assertThat(PriorityBand.of(10.0)).isEqualTo(PriorityBand.CRITICAL);
        }

        @Test
        @DisplayName("wire values are lowercase, matching the frontend union type")
        void wireValues() {
            assertThat(PriorityBand.LOW.wireValue()).isEqualTo("low");
            assertThat(PriorityBand.MEDIUM.wireValue()).isEqualTo("medium");
            assertThat(PriorityBand.HIGH.wireValue()).isEqualTo("high");
            assertThat(PriorityBand.CRITICAL.wireValue()).isEqualTo("critical");
        }
    }

    @Nested
    @DisplayName("reasons")
    class Reasons {

        @Test
        @DisplayName("a single report says so rather than returning nothing")
        void singleReportIsExplained() {
            PriorityResult result = service.compute(
                    incidentCreated(NOW),
                    List.of(complaint(NOW, BASE_LAT, BASE_LON)),
                    NOW);

            // An empty list would read in the UI as "we cannot explain this
            // score", which is not what is happening.
            assertThat(result.reasons())
                    .containsExactly("Single report — awaiting corroboration");
        }

        @Test
        @DisplayName("evidence is stated in plain language, never as weights or codes")
        void plainLanguage() {
            Incident incident = incidentCreated(NOW.minusDays(21));
            List<Complaint> complaints = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                complaints.add(complaint(NOW.minusHours(3), BASE_LAT, BASE_LON));
            }
            for (int i = 0; i < 9; i++) {
                complaints.add(complaint(NOW.minusDays(15), BASE_LAT, BASE_LON));
            }

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.reasons())
                    .contains("12 complaints reported")
                    .contains("3 new complaints in the last 24 hours")
                    .contains("Open for 21 days without resolution");
        }

        @Test
        @DisplayName("singular is respected for a single new report")
        void singularGrammar() {
            Incident incident = incidentCreated(NOW.minusDays(5));
            List<Complaint> complaints = List.of(
                    complaint(NOW.minusHours(1), BASE_LAT, BASE_LON),
                    complaint(NOW.minusDays(4), BASE_LAT, BASE_LON),
                    complaint(NOW.minusDays(4), BASE_LAT, BASE_LON));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.reasons()).contains("1 new complaint in the last 24 hours");
        }

        @Test
        @DisplayName("tight clustering is reported as corroboration")
        void tightCluster() {
            Incident incident = incidentCreated(NOW.minusDays(4));
            List<Complaint> complaints = List.of(
                    complaint(NOW.minusDays(1), BASE_LAT, BASE_LON),
                    complaint(NOW.minusDays(2), BASE_LAT + 0.0002, BASE_LON),
                    complaint(NOW.minusDays(3), BASE_LAT, BASE_LON + 0.0002));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.reasons())
                    .anyMatch(reason -> reason.startsWith("Reports concentrated within"));
        }

        @Test
        @DisplayName("a wide group reports its span in readable units")
        void wideSpread() {
            Incident incident = incidentCreated(NOW.minusDays(2));
            List<Complaint> complaints = List.of(
                    complaint(NOW.minusDays(1), BASE_LAT, BASE_LON),
                    complaint(NOW.minusDays(1), LAT_400M, BASE_LON));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.reasons()).contains("Spread across roughly 400 m");
        }

        @Test
        @DisplayName("never more than four, because the panel renders them as chips")
        void capped() {
            Incident incident = incidentCreated(NOW.minusDays(30));
            List<Complaint> complaints = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                complaints.add(complaint(NOW.minusHours(2), BASE_LAT, BASE_LON));
            }
            for (int i = 0; i < 5; i++) {
                complaints.add(complaint(NOW.minusDays(3), BASE_LAT, BASE_LON));
            }
            complaints.add(complaint(NOW.minusDays(3), LAT_400M, BASE_LON));

            PriorityResult result = service.compute(incident, complaints, NOW);

            assertThat(result.reasons()).hasSizeLessThanOrEqualTo(4);
        }
    }

    private static Incident incidentCreated(LocalDateTime createdAt) {
        Incident incident = Incident.builder().category("pothole").build();
        incident.setCreatedAt(createdAt);
        return incident;
    }

    private static Complaint complaint(LocalDateTime createdAt, double lat, double lon) {
        Complaint complaint = Complaint.builder()
                .description("test")
                .category("pothole")
                .latitude(lat)
                .longitude(lon)
                .build();
        complaint.setCreatedAt(createdAt);
        return complaint;
    }
}
