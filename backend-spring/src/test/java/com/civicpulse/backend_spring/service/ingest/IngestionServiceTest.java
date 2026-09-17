package com.civicpulse.backend_spring.service.ingest;

import com.civicpulse.backend_spring.dto.ingest.ExternalComplaint;
import com.civicpulse.backend_spring.dto.ingest.IngestionReport;
import com.civicpulse.backend_spring.entity.IngestionBatch;
import com.civicpulse.backend_spring.enums.IngestionOutcome;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.IngestionBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The batch loop — the half of ingestion that is NOT per-record validation.
 *
 * <p>Its whole job is isolation: one record must not be able to affect the rest.
 * That property is invisible in the happy path and only shows up on the day a
 * feed sends something that makes the pipeline throw, which is exactly when
 * nobody is in a position to debug it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionServiceTest {

    @Mock private IngestionBatchRepository batchRepository;
    @Mock private IngestionRecordProcessor processor;

    private IngestionService service;
    private final List<IngestionBatch> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new IngestionService(batchRepository, processor);
        saved.clear();
        when(batchRepository.save(any())).thenAnswer(invocation -> {
            IngestionBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(1L);
            }
            saved.add(batch);
            return batch;
        });
    }

    private static ExternalComplaint record(String id) {
        ExternalComplaint c = new ExternalComplaint();
        c.setSourceRecordId(id);
        return c;
    }

    @Test
    @DisplayName("one record that THROWS does not abandon the rest of the batch")
    void oneThrowingRecordDoesNotStopTheBatch() {
        // The isolation guarantee, stated as a test. A malformed row at
        // position 5 of 10 must cost that row and nothing else — a batch that
        // is all-or-nothing makes a large feed impossible to onboard, because
        // every run dies on whichever row is worst that day.
        List<ExternalComplaint> batch =
                IntStream.rangeClosed(1, 10).mapToObj(i -> record("R-" + i)).toList();

        when(processor.process(anyString(), anyLong(), any(), any(), any()))
                .thenReturn(IngestionOutcome.ACCEPTED);
        when(processor.process(anyString(), anyLong(), eq(batch.get(4)), any(), any()))
                .thenThrow(new IllegalStateException("upstream sent something impossible"));

        IngestionReport report = service.ingest("bbmp", batch);

        // Nine processed, one recorded as a failure — and crucially, the loop
        // reached records 6..10 at all.
        assertThat(report.received()).isEqualTo(10);
        assertThat(report.accepted()).isEqualTo(9);
        assertThat(report.rejected()).isEqualTo(1);
        verify(processor, times(10)).process(anyString(), anyLong(), any(), any(), any());
        verify(processor).recordFailure(eq("bbmp"), anyLong(), eq(batch.get(4)), any());
    }

    @Test
    @DisplayName("a failure while RECORDING a failure still does not stop the batch")
    void failureToRecordAFailureIsContained() {
        // The nastiest case: the database is refusing writes. There is nothing
        // useful to do about it per-record, but taking the whole process down
        // mid-batch leaves the batch row saying "in flight" forever.
        List<ExternalComplaint> batch = List.of(record("R-1"), record("R-2"));
        when(processor.process(anyString(), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        org.mockito.Mockito.doThrow(new IllegalStateException("cannot write either"))
                .when(processor).recordFailure(anyString(), anyLong(), any(), any());

        IngestionReport report = service.ingest("bbmp", batch);

        assertThat(report.rejected()).isEqualTo(2);
        verify(processor, times(2)).process(anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("the batch row is always closed out, with counts derived from outcomes")
    void batchIsFinalised() {
        when(processor.process(anyString(), anyLong(), any(), any(), any()))
                .thenReturn(IngestionOutcome.ACCEPTED, IngestionOutcome.REJECTED,
                        IngestionOutcome.UNCHANGED, IngestionOutcome.DUPLICATE,
                        IngestionOutcome.UPDATED);

        IngestionReport report = service.ingest("bbmp",
                IntStream.rangeClosed(1, 5).mapToObj(i -> record("R-" + i)).toList());

        // ACCEPTED + UPDATED both count as accepted; UNCHANGED + DUPLICATE both
        // count as "nothing written", which is what `unchanged` means on the wire.
        assertThat(report.accepted()).isEqualTo(2);
        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(2);

        IngestionBatch finalised = saved.get(saved.size() - 1);
        assertThat(finalised.getFinishedAt())
                .as("a batch left with finishedAt null reads as permanently in flight")
                .isNotNull();
        assertThat(finalised.getReceived()).isEqualTo(5);
    }

    @Test
    @DisplayName("an unusable request is refused before a batch row is created")
    void refusesUnusableRequests() {
        assertThatThrownBy(() -> service.ingest("bbmp", List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.ingest("bbmp", null))
                .isInstanceOf(ValidationException.class);

        List<ExternalComplaint> tooMany =
                IntStream.rangeClosed(1, IngestionService.MAX_BATCH_SIZE + 1)
                        .mapToObj(i -> record("R-" + i)).toList();
        assertThatThrownBy(() -> service.ingest("bbmp", tooMany))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds the maximum");

        // No half-open batch row left behind for any of them.
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("the per-record methods are annotated REQUIRES_NEW on a DIFFERENT bean")
    void perRecordTransactionBoundaryIsReal() throws Exception {
        // This is the structural half of the isolation guarantee, and it is
        // worth asserting because it is so easy to undo: moving process() back
        // onto IngestionService — an inviting "simplification" — would make the
        // annotation a no-op, since @Transactional is proxy-based and a
        // self-invocation never crosses the proxy. Everything would still
        // compile, every other test would still pass, and one bad row would
        // start rolling back the whole batch.
        for (String name : List.of("process", "recordFailure")) {
            Method method = java.util.Arrays.stream(
                            IngestionRecordProcessor.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst().orElseThrow();

            var annotation = method.getAnnotation(
                    org.springframework.transaction.annotation.Transactional.class);
            assertThat(annotation).as("%s must be transactional", name).isNotNull();
            assertThat(annotation.propagation())
                    .as("%s must start its own transaction", name)
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        }

        assertThat(IngestionRecordProcessor.class)
                .as("the per-record unit must not live on the batch bean")
                .isNotEqualTo(IngestionService.class);

        // And the batch loop itself must NOT be transactional, or it would
        // enclose every record in one outer transaction and defeat the point.
        assertThat(IngestionService.class.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class))
                .as("the batch loop must hold no transaction of its own")
                .isNull();
        assertThat(java.util.Arrays.stream(IngestionService.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.transaction.annotation.Transactional.class)))
                .as("no method on the batch loop may be transactional")
                .isFalse();
    }
}
