package com.civicpulse.backend_spring.service.matching;

import com.civicpulse.backend_spring.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingCircuitBreakerTest {

    private static MatchingCircuitBreaker breaker(int threshold, long openSeconds) {
        AppProperties props = new AppProperties();
        props.getMatching().getCircuitBreaker().setFailureThreshold(threshold);
        props.getMatching().getCircuitBreaker().setOpenSeconds(openSeconds);
        return new MatchingCircuitBreaker(props);
    }

    @Test
    @DisplayName("stays closed until the failure threshold, then opens")
    void opensAtThreshold() {
        MatchingCircuitBreaker breaker = breaker(3, 60);

        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @DisplayName("a success resets the failure count")
    void successResets() {
        MatchingCircuitBreaker breaker = breaker(3, 60);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        // only two failures since the reset — still closed
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("reopens for a trial call once the cooldown elapses")
    void halfOpensAfterCooldown() throws InterruptedException {
        MatchingCircuitBreaker breaker = breaker(1, 1);

        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();

        Thread.sleep(1_100);

        assertThat(breaker.allowRequest()).isTrue();
    }
}
