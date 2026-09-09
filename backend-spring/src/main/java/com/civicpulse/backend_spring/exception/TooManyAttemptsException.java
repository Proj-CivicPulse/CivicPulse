package com.civicpulse.backend_spring.exception;

import lombok.Getter;

/**
 * Thrown when a login budget is exhausted. Carries the remaining lockout so the
 * handler can set {@code Retry-After} and the message can tell the user when to
 * come back rather than just refusing them.
 */
@Getter
public class TooManyAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
