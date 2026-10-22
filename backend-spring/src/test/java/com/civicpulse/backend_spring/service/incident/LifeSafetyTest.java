package com.civicpulse.backend_spring.service.incident;

import com.civicpulse.backend_spring.dto.incident.UpdateIncidentRequest;
import com.civicpulse.backend_spring.entity.Complaint;
import com.civicpulse.backend_spring.entity.Incident;
import com.civicpulse.backend_spring.enums.IncidentStatus;
import com.civicpulse.backend_spring.enums.LifeSafetySource;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.ComplaintRepository;
import com.civicpulse.backend_spring.repository.DepartmentRepository;
import com.civicpulse.backend_spring.repository.IncidentRepository;
import com.civicpulse.backend_spring.service.category.CategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The life-safety flag (V24): what the detector flags, that a text suggestion
 * follows the members, and that an officer's decision is final.
 */
class LifeSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Storm drain cover is missing. Someone is going to fall into it at night.",
            "Open manhole on 5th Cross, no barricade",
            "Drain is completely blocked. Sewage backing up onto the road outside the houses.",
            "Surface has collapsed where they dug for the cable last month.",
            "Live wire hanging from the pole near the school gate",
            "Streetlight pole sparking when it rains",
            "Huge tree has fallen across the road after the storm",
    })
    @DisplayName("hazards that can kill someone are flagged")
    void flags(String text) {
        assertThat(LifeSafetyDetector.detect(List.of(text))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Garbage has not been collected for over a week.",
            "Street light has been out for three weeks.",
            "Pothole right at the junction. Autos slow to a crawl.",
            "Water pipe has been leaking for days.",
            "They dug for the cable last month and left the road uneven.",
    })
    @DisplayName("ordinary faults are not")
    void ignores(String text) {
        assertThat(LifeSafetyDetector.detect(List.of(text))).isEmpty();
    }

    @Test
    @DisplayName("the reason names the hazard and quotes the words")
    void reason() {
        LifeSafetyDetector.Finding f = LifeSafetyDetector
                .detect(List.of("Storm drain cover is missing near the school")).orElseThrow();
        assertThat(f.hazard()).isEqualTo("an open drain or manhole");
        assertThat(f.reason()).isEqualTo("Reports mention an open drain or manhole: “drain cover is missing”");
    }

    private static Complaint member(String text) {
        return Complaint.builder().description(text).build();
    }

    @Test
    @DisplayName("a text suggestion follows the members, both ways")
    void followsMembers() {
        Incident incident = Incident.builder().id(1L).build();

        IncidentAttachmentService.applyLifeSafety(incident, List.of(member("Open manhole near the bus stop")));
        assertThat(incident.isLifeSafety()).isTrue();
        assertThat(incident.getLifeSafetySource()).isEqualTo(LifeSafetySource.REPORT_TEXT);

        // The hazardous report moved off (unlink): the suggestion goes with it.
        IncidentAttachmentService.applyLifeSafety(incident, List.of(member("Pothole near the bus stop")));
        assertThat(incident.isLifeSafety()).isFalse();
        assertThat(incident.getLifeSafetySource()).isNull();
        assertThat(incident.getLifeSafetyReason()).isNull();
    }

    @Test
    @DisplayName("an officer's decision is final: new reports do not reopen it")
    void officerIsFinal() {
        Incident cleared = Incident.builder().id(1L).lifeSafety(false)
                .lifeSafetySource(LifeSafetySource.OFFICER).build();
        IncidentAttachmentService.applyLifeSafety(cleared, List.of(member("Open manhole near the bus stop")));
        assertThat(cleared.isLifeSafety()).isFalse();

        Incident confirmed = Incident.builder().id(2L).lifeSafety(true)
                .lifeSafetySource(LifeSafetySource.OFFICER).lifeSafetyReason("Officer: wires on the road").build();
        IncidentAttachmentService.applyLifeSafety(confirmed, List.of(member("Pothole")));
        assertThat(confirmed.isLifeSafety()).isTrue();
        assertThat(confirmed.getLifeSafetyReason()).isEqualTo("Officer: wires on the road");
    }

    private static IncidentService service(Incident incident) {
        IncidentRepository incidents = mock(IncidentRepository.class);
        when(incidents.findById(incident.getId())).thenReturn(Optional.of(incident));
        when(incidents.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));
        return new IncidentService(incidents, mock(ComplaintRepository.class),
                mock(DepartmentRepository.class), mock(CategoryService.class));
    }

    private static UpdateIncidentRequest flag(Boolean value, String note) {
        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setLifeSafety(value);
        request.setLifeSafetyNote(note);
        return request;
    }

    @Test
    @DisplayName("PATCH: confirming keeps the quoted reason; clearing keeps none; a note replaces it")
    void patch() {
        Incident incident = Incident.builder().id(7L).status(IncidentStatus.OPEN).lifeSafety(true)
                .lifeSafetySource(LifeSafetySource.REPORT_TEXT)
                .lifeSafetyReason("Reports mention an open drain or manhole: “open manhole”").build();
        IncidentService service = service(incident);

        var confirmed = service.update(7L, flag(true, null));
        assertThat(confirmed.isLifeSafety()).isTrue();
        assertThat(confirmed.getLifeSafetySource()).isEqualTo("officer");
        assertThat(confirmed.getLifeSafetyReason()).contains("open manhole");

        var cleared = service.update(7L, flag(false, null));
        assertThat(cleared.isLifeSafety()).isFalse();
        assertThat(cleared.getLifeSafetySource()).isEqualTo("officer");
        assertThat(cleared.getLifeSafetyReason()).isNull();

        var noted = service.update(7L, flag(true, "  wires lying in water  "));
        assertThat(noted.getLifeSafetyReason()).isEqualTo("Officer: wires lying in water");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    @DisplayName("PATCH: a note without a decision is refused")
    void noteNeedsDecision() {
        IncidentService service = service(Incident.builder().id(8L).status(IncidentStatus.OPEN).build());
        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setStatus("in_progress");
        request.setLifeSafetyNote("looks dangerous");
        assertThatThrownBy(() -> service.update(8L, request)).isInstanceOf(ValidationException.class);
    }
}
