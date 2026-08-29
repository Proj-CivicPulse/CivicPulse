package com.civicpulse.backend_spring.exception;

/**
 * The caller is not authenticated, or their session is no longer usable
 * (missing/expired/invalid token). Rendered as 401 UNAUTHORIZED, which the
 * frontend treats as its cue to attempt a refresh and then log out.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
