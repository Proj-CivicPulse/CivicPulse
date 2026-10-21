package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.dto.copilot.CopilotAnswerDto;
import com.civicpulse.backend_spring.exception.TooManyAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The checks the Copilot applies before an officer sees an answer, and its per-officer budget. */
class CopilotServiceTest {

    private static CopilotPacketBuilder.Packet packet() {
        Map<String, CopilotAnswerDto.Source> sources = new LinkedHashMap<>();
        sources.put("incident:45", new CopilotAnswerDto.Source("incident:45", "incident", "Pothole · 5th Cross Rd", "45"));
        sources.put("ward:538", new CopilotAnswerDto.Source("ward:538", "ward", "Jnana Bharathi (ward 48)", "538"));
        sources.put("metric:active_incidents", new CopilotAnswerDto.Source("metric:active_incidents", "metric", "Active", null));
        CopilotAnswerDto.Scope scope = new CopilotAnswerDto.Scope(null, null, null, null, null, null);
        return new CopilotPacketBuilder.Packet(Map.of(), sources, scope);
    }

    @Test
    @DisplayName("keeps only citations the packet contained, in order, once each — and counts the rest")
    void dropsCitationsNotInThePacket() {
        var answer = new NodeCopilotClient.NodeAnswer(true, "Incident 45 is most urgent.",
                List.of("incident:45", "incident:999", "ward:538", "incident:45", " metric:active_incidents "),
                "claude-opus-5-5", false);

        CopilotAnswerDto dto = CopilotService.checked(answer, packet());

        assertThat(dto.getSources()).extracting(CopilotAnswerDto.Source::getId)
                .containsExactly("incident:45", "ward:538", "metric:active_incidents");
        assertThat(dto.getDroppedCitations()).isEqualTo(1);
        assertThat(dto.isUngrounded()).isFalse();
        assertThat(dto.getModel()).isEqualTo("claude-opus-5-5");
    }

    @Test
    @DisplayName("an answer with no valid citation is flagged ungrounded, not presented as fact")
    void flagsUngroundedAnswers() {
        var invented = new NodeCopilotClient.NodeAnswer(true, "Ward 12 has 40 potholes.",
                List.of("ward:12"), "claude-opus-5-5", false);
        CopilotAnswerDto dto = CopilotService.checked(invented, packet());

        assertThat(dto.isUngrounded()).isTrue();
        assertThat(dto.getSources()).isEmpty();
        assertThat(dto.getDroppedCitations()).isEqualTo(1);
    }

    @Test
    @DisplayName("an honest 'cannot answer' or a refusal is not 'ungrounded' — it claimed nothing")
    void unanswerableIsNotUngrounded() {
        var cannot = new NodeCopilotClient.NodeAnswer(false, "The data does not cover the weather.",
                List.of(), "claude-opus-5-5", false);
        assertThat(CopilotService.checked(cannot, packet()).isUngrounded()).isFalse();

        var refused = new NodeCopilotClient.NodeAnswer(false, "The Copilot could not answer this question.",
                null, "claude-opus-5-5", true);
        CopilotAnswerDto dto = CopilotService.checked(refused, packet());
        assertThat(dto.isRefused()).isTrue();
        assertThat(dto.isUngrounded()).isFalse();
        assertThat(dto.getSources()).isEmpty();
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("rate limit: the budget is per officer, per hour, with an honest Retry-After")
    void ratesPerOfficerPerHour() {
        AppProperties properties = new AppProperties();
        properties.getCopilot().setMaxQuestionsPerHour(3);
        MovableClock clock = new MovableClock();
        CopilotRateLimiter limiter = new CopilotRateLimiter(properties, clock);

        limiter.acquire(1L);
        limiter.acquire(1L);
        limiter.acquire(1L);
        // Another officer has their own budget.
        limiter.acquire(2L);

        clock.now = clock.now.plusSeconds(600);
        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOf(TooManyAttemptsException.class)
                .satisfies(ex -> assertThat(((TooManyAttemptsException) ex).getRetryAfterSeconds()).isEqualTo(3000));

        // After the window, the budget resets.
        clock.now = clock.now.plusSeconds(3001);
        limiter.acquire(1L);
    }
}
