package com.civicpulse.backend_spring.service.copilot;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.exception.TooManyAttemptsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Questions per officer per hour. Every question is a metered model call, so
 * the budget is per PERSON — the only place that knows who is asking is here,
 * behind the session, which is why it lives in Spring and not in Node.
 *
 * A fixed window per officer, in memory: one Spring instance serves the app,
 * and a restart forgiving a budget is harmless. Answers 429 with Retry-After,
 * the same envelope as the sign-in lockout.
 */
@Component
public class CopilotRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private record Window(Instant start, int count) {
    }

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties appProperties;
    private final Clock clock;

    @Autowired
    public CopilotRateLimiter(AppProperties appProperties) {
        this(appProperties, Clock.systemUTC());
    }

    CopilotRateLimiter(AppProperties appProperties, Clock clock) {
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /** Counts one question, or refuses it with how long until the window resets. */
    public void acquire(Long userId) {
        int max = appProperties.getCopilot().getMaxQuestionsPerHour();
        Instant now = clock.instant();
        Window updated = windows.compute(userId, (id, current) -> {
            if (current == null || !now.isBefore(current.start().plus(WINDOW))) {
                return new Window(now, 1);
            }
            return new Window(current.start(), current.count() + 1);
        });
        if (updated.count() > max) {
            long retryAfter = Math.max(1, Duration.between(now, updated.start().plus(WINDOW)).toSeconds());
            throw new TooManyAttemptsException(
                    "You have asked the Copilot " + max + " questions this hour — try again in "
                            + Math.max(1, retryAfter / 60) + " minutes",
                    retryAfter);
        }
    }
}
