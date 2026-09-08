package com.civicpulse.backend_spring.service.matching;

import com.civicpulse.backend_spring.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny consecutive-failure circuit breaker around the backend-node trigger.
 *
 * Once Node has failed to connect {@code failure-threshold} times in a row, new
 * complaints skip the trigger entirely for {@code open-seconds} and go straight
 * to the naive fallback — so a Node outage does not make every submission's
 * background worker pay a connect timeout.
 *
 * <p><b>State is per-JVM.</b> Behind multiple replicas each instance keeps its
 * own breaker; that is fine for the single-instance deployment this project
 * targets. If it ever runs multi-replica, revisit — the trigger is cheap enough
 * that independent breakers are acceptable, but it should be a deliberate call.
 */
@Component
@Slf4j
public class MatchingCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openUntilEpochMs = 0L;

    public MatchingCircuitBreaker(AppProperties appProperties) {
        AppProperties.Matching.CircuitBreaker config =
                appProperties.getMatching().getCircuitBreaker();
        this.failureThreshold = config.getFailureThreshold();
        this.openMillis = config.getOpenSeconds() * 1000L;
    }

    /** False while the breaker is open. When it reopens after the cooldown, one trial call is allowed through. */
    public boolean allowRequest() {
        return System.currentTimeMillis() >= openUntilEpochMs;
    }

    public boolean isOpen() {
        return !allowRequest();
    }

    public void recordSuccess() {
        if (consecutiveFailures.getAndSet(0) >= failureThreshold) {
            log.info("Matching circuit closed — backend-node is responding again");
        }
        openUntilEpochMs = 0L;
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntilEpochMs = System.currentTimeMillis() + openMillis;
            log.warn("Matching circuit opened after {} consecutive failures — "
                    + "backend-node calls suspended for {}s", failures, openMillis / 1000L);
        }
    }
}
