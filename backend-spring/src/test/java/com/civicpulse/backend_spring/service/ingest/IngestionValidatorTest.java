package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.entity.Category;
import com.civicpulse.backend_spring.entity.Ward;
import com.civicpulse.backend_spring.enums.ComplaintStatus;
import com.civicpulse.backend_spring.repository.WardRepository;
import com.civicpulse.backend_spring.service.category.CategoryService;
import com.civicpulse.backend_spring.service.ward.WardResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The gate itself — ISSUE-10.
 *
 * <p>Each test here is a way real external data goes wrong. The point of every
 * one is the same: bad input must produce an attributable REASON, never an
 * exception and never a silently-written row, because a record that lands in
 * {@code complaints} with a broken ward or a fabricated timestamp corrupts
 * matching and every priority score derived from it, invisibly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionValidatorTest {

    @Mock private CategoryService categoryService;
    @Mock private WardResolver wardResolver;
    @Mock private WardRepository wardRepository;

    private IngestionValidator validator;
    private LocalDateTime now;

    private static final Category POTHOLE = Category.builder()
            .id(1L).code("pothole").name("Pothole / road damage")
            .displayName("Pothole or damaged road").active(true).build();

    private static final Ward WARD_17 = Ward.builder()
            .id(17L).code("17").name("Jayanagar").active(true)
            .sourceWardCode("2003017").lgdWardCode("1302700").build();

    @BeforeEach
    void setUp() {
        validator = new IngestionValidator(categoryService, wardResolver, wardRepository);
        now = LocalDateTime.of(2026, 9, 14, 12, 0);

        when(categoryService.resolve(anyString(), anyString())).thenReturn(Optional.empty());
        when(categoryService.resolve(eq("Road Damage"))).thenReturn(Optional.of(POTHOLE));
        when(categoryService.resolve(eq("pothole"))).thenReturn(Optional.of(POTHOLE));
        when(categoryService.resolve(anyString())).thenAnswer(inv ->
                List.of("Road Damage", "pothole").contains(inv.getArgument(0))
                        ? Optional.of(POTHOLE) : Optional.empty());
        when(wardResolver.resolve(anyDouble(), anyDouble())).thenReturn(Optional.of(WARD_17));
        when(wardRepository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(WARD_17));
    }

    private static ExternalComplaint valid() {
        ExternalComplaint c = new ExternalComplaint();
        c.setSourceRecordId("BBMP-2026-0001");
        c.setDescription("Large pothole outside the school gate");
        c.setCategory("Road Damage");
        c.setLat("12.9250");
        c.setLng("77.5938");
        c.setReportedAt("2026-09-10T08:30:00Z");
        c.setStatus("REGISTERED");
        return c;
    }

    @Test
    @DisplayName("a good record passes and comes out canonical")
    void acceptsAndNormalises() {
        IngestionValidator.Result result = validator.validate("bbmp", valid(), now);

        assertThat(result.rejected()).isFalse();
        IngestionValidator.Validated v = result.validated();
        // The feed's spelling is resolved to our code, and its own wording kept.
        assertThat(v.category().getCode()).isEqualTo("pothole");
        assertThat(v.rawCategory()).isEqualTo("Road Damage");
        // The feed's status vocabulary is mapped to ours, not trusted.
        assertThat(v.status()).isEqualTo(ComplaintStatus.OPEN);
        assertThat(v.ward()).isEqualTo(WARD_17);
        assertThat(v.reportedAt()).isEqualTo(LocalDateTime.of(2026, 9, 10, 8, 30));
    }

    @Test
    @DisplayName("reports EVERY problem at once, not just the first")
    void collectsAllReasons() {
        // A feed being onboarded is usually wrong in several ways at once.
        // Reporting one fault per round trip turns that into a week of emails.
        ExternalComplaint bad = new ExternalComplaint();
        bad.setDescription("   ");
        bad.setCategory("interpretive dance");
        bad.setLat("not-a-number");
        bad.setLng("999");
        bad.setReportedAt("last Tuesday");
        bad.setStatus("QUANTUM");

        IngestionValidator.Result result = validator.validate("bbmp", bad, now);

        assertThat(result.rejected()).isTrue();
        assertThat(result.reasons()).extracting(RejectionReason::field)
                .contains("sourceRecordId", "description", "category", "lat", "lng",
                        "reportedAt", "status");
    }

    @Test
    @DisplayName("a record with no upstream id is refused — without one nothing can be idempotent")
    void requiresSourceRecordId() {
        ExternalComplaint c = valid();
        c.setSourceRecordId(null);

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> {
                    assertThat(r.field()).isEqualTo("sourceRecordId");
                    assertThat(r.code()).isEqualTo("MISSING");
                });
    }

    @Test
    @DisplayName("(0, 0) is refused as a failed-geocoder sentinel, not accepted as the Atlantic")
    void rejectsNullIsland() {
        // A feed emits 0,0 when its own geocoding failed. Accepting it would put
        // reports a thousand miles out to sea AND cluster every such failure
        // into one incident.
        ExternalComplaint c = valid();
        c.setLat("0");
        c.setLng("0");

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> assertThat(r.detail()).contains("null-island"));
    }

    @Test
    @DisplayName("a coordinate outside every ward is reported, never snapped to the nearest")
    void rejectsPointOutsideEveryWard() {
        when(wardResolver.resolve(anyDouble(), anyDouble())).thenReturn(Optional.empty());

        IngestionValidator.Result result = validator.validate("bbmp", valid(), now);

        assertThat(result.rejected()).isTrue();
        assertThat(result.reasons())
                .anySatisfy(r -> assertThat(r.code()).isEqualTo("NO_WARD_AT_LOCATION"));
    }

    @Test
    @DisplayName("an upstream ward code we cannot map is reported, never guessed")
    void rejectsUnmappableWardCode() {
        ExternalComplaint c = valid();
        c.setWardCode("WARD-FROM-ANOTHER-CITY");

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> {
                    assertThat(r.field()).isEqualTo("wardCode");
                    assertThat(r.code()).isEqualTo("UNKNOWN_VALUE");
                });
    }

    @Test
    @DisplayName("an upstream ward code maps through any of the identifiers we hold")
    void mapsWardByAnyKnownIdentifier() {
        // The crosswalk in practice: a feed may quote our ward number, the KGIS
        // code, or the national LGD code, and all three are on the ward row.
        for (String code : List.of("17", "2003017", "1302700")) {
            ExternalComplaint c = valid();
            c.setWardCode(code);

            IngestionValidator.Result result = validator.validate("bbmp", c, now);
            assertThat(result.rejected()).as("ward code %s should map", code).isFalse();
            assertThat(result.validated().ward()).isEqualTo(WARD_17);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-09-10T08:30:00Z",
            "2026-09-10T08:30:00",
            "2026-09-10 08:30:00",
            "2026-09-10 08:30",
            "2026-09-10",
    })
    @DisplayName("accepts each declared timestamp format")
    void acceptsDeclaredTimestampFormats(String value) {
        ExternalComplaint c = valid();
        c.setReportedAt(value);

        assertThat(validator.validate("bbmp", c, now).rejected()).isFalse();
    }

    @Test
    @DisplayName("an ambiguous date format is refused rather than guessed")
    void refusesAmbiguousDates() {
        // 03/04/2026 is March in one country and April in another. Guessing
        // silently corrupts the age term of every score derived from it, and
        // there is no way to detect it afterwards.
        ExternalComplaint c = valid();
        c.setReportedAt("03/04/2026");

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> {
                    assertThat(r.field()).isEqualTo("reportedAt");
                    assertThat(r.code()).isEqualTo("UNPARSEABLE");
                });
    }

    @Test
    @DisplayName("a report from the future is refused — it would break age and growth")
    void refusesFutureTimestamps() {
        ExternalComplaint c = valid();
        c.setReportedAt("2027-01-01T00:00:00Z");

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> assertThat(r.detail()).contains("future"));
    }

    @Test
    @DisplayName("a missing timestamp means now, because a feed without one is still worth having")
    void absentTimestampDefaultsToNow() {
        ExternalComplaint c = valid();
        c.setReportedAt(null);

        IngestionValidator.Result result = validator.validate("bbmp", c, now);
        assertThat(result.rejected()).isFalse();
        assertThat(result.validated().reportedAt()).isEqualTo(now);
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "NEW", "Registered", "pending"})
    @DisplayName("a feed's status vocabulary normalises onto ours")
    void normalisesStatus(String value) {
        ExternalComplaint c = valid();
        c.setStatus(value);

        assertThat(validator.validate("bbmp", c, now).validated().status())
                .isEqualTo(ComplaintStatus.OPEN);
    }

    @Test
    @DisplayName("separators and case do not create new statuses")
    void statusSeparatorsFold() {
        for (String value : List.of("IN PROGRESS", "in-progress", "In_Progress", "WIP")) {
            ExternalComplaint c = valid();
            c.setStatus(value);
            assertThat(validator.validate("bbmp", c, now).validated().status())
                    .as("status %s", value)
                    .isEqualTo(ComplaintStatus.IN_PROGRESS);
        }
    }

    @Test
    @DisplayName("an unrecognised status is refused rather than defaulted to OPEN")
    void refusesUnknownStatus() {
        // Defaulting would quietly reopen work an upstream system had closed.
        ExternalComplaint c = valid();
        c.setStatus("ESCALATED_TO_TRIBUNAL");

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> assertThat(r.field()).isEqualTo("status"));
    }

    @Test
    @DisplayName("oversized text is refused, not truncated")
    void refusesOversizedText() {
        // Silently truncating puts half a sentence in front of an officer and
        // calls it the report.
        ExternalComplaint c = valid();
        c.setDescription("x".repeat(5001));

        assertThat(validator.validate("bbmp", c, now).reasons())
                .anySatisfy(r -> {
                    assertThat(r.field()).isEqualTo("description");
                    assertThat(r.code()).isEqualTo("TOO_LONG");
                });
    }

    @Test
    @DisplayName("a feed's own vocabulary wins over the shared one")
    void sourceScopedAliasesTakePrecedence() {
        Category garbage = Category.builder()
                .id(3L).code("garbage").name("Solid waste").displayName("x").active(true).build();
        // In this feed, "road damage" means something else entirely. Scoping is
        // what stops one publisher's terminology redefining ours for everyone.
        when(categoryService.resolve(eq("Road Damage"), eq("otherfeed")))
                .thenReturn(Optional.of(garbage));

        ExternalComplaint c = valid();
        IngestionValidator.Result result = validator.validate("otherfeed", c, now);

        assertThat(result.validated().category().getCode()).isEqualTo("garbage");
    }

    @Test
    @DisplayName("never throws, whatever arrives")
    void neverThrows() {
        // The gate's contract: a malformed record produces reasons, not an
        // exception that takes down the batch around it.
        ExternalComplaint empty = new ExternalComplaint();
        assertThat(validator.validate("bbmp", empty, now).rejected()).isTrue();

        ExternalComplaint weird = new ExternalComplaint();
        weird.setSourceRecordId(" �");
        weird.setLat("NaN");
        weird.setLng("Infinity");
        weird.setDescription("ok");
        weird.setCategory("pothole");
        assertThat(validator.validate("bbmp", weird, now).rejected()).isTrue();
    }
}
