package com.civicpulse.backend_spring.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Renders every error in the shared contract shape (see {@link ApiError}).
 *
 * Rule: 4xx responses may explain what the caller did wrong; 5xx responses
 * are deliberately opaque and the real cause goes to the log only. Never
 * echo submitted values, SQL, or stack traces back to the client.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return build(HttpStatus.CONFLICT, ErrorCode.EMAIL_ALREADY_EXISTS, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        // Deliberately identical for "no such email" and "wrong password" —
        // distinguishing them lets an attacker enumerate registered accounts.
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(ValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    /**
     * A path variable or query parameter could not be converted to its target
     * type — GET /complaints/abc, or ?minPriority=foo.
     *
     * Without this the request falls through to the catch-all below and the
     * caller gets a 500 for what is plainly their own malformed input.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        String expected = required == null ? "valid value" : required.getSimpleName().toLowerCase();
        // Names the parameter and the expected type, never the value submitted.
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ex.getName() + " must be a valid " + expected);
    }

    /** A required query parameter was absent, e.g. /wards/resolve with no lat. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                ex.getParameterName() + " is required");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationExceptions(MethodArgumentNotValidException ex) {
        // Field names and the rule they broke are safe to return; the values
        // the caller submitted are not echoed.
        String message = ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Validation failed";
        }

        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body", ex);
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_JSON, "Request body is not valid JSON");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this endpoint");
    }

    /**
     * Body sent with a Content-Type this endpoint cannot read. That is the
     * caller getting the request wrong, so it is a 415 — without this it fell
     * through to the catch-all and reported INTERNAL_ERROR for a client
     * mistake, the same failure mode as the type-mismatch and missing-param
     * handlers above.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type " + ex.getContentType() + " is not supported by this endpoint");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                "Cannot " + ex.getHttpMethod() + " /" + ex.getResourcePath());
    }

    /**
     * The client hung up before the response could be flushed — most often
     * backend-node being restarted mid-{@code /internal/incidents/attach}, or a
     * browser navigating away. Nothing failed on this side and there is no
     * longer a socket to answer on, so this is a DEBUG line, not an ERROR with
     * a stack trace.
     *
     * Worth handling explicitly: it happens precisely during the Node outages
     * Phase 2 is built to absorb, and at ERROR it would bury the real failures
     * in that same window.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<ApiError> handleClientDisconnected(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before the response was written: {}", ex.getMessage());
        // Nothing can reach the caller; the body is a formality for the framework.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    /**
     * Catch-all. Anything reaching here is a bug or an unhandled downstream
     * failure: log it in full, tell the client nothing.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred");
    }
}
