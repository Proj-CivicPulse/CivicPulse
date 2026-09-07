package com.civicpulse.backend_spring.exception;

/**
 * Stable, machine-readable error codes. UPPER_SNAKE_CASE, shared with
 * backend-node (src/middleware/errorHandler.ts) so clients can branch on a
 * code regardless of which service answered.
 *
 * Codes are part of the API contract — rename one only alongside
 * docs/api-contract.md and the frontend.
 */
public final class ErrorCode {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_JSON = "INVALID_JSON";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    /** Already emitted by backend-node; Spring now shares the same code. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCode() {
    }
}
