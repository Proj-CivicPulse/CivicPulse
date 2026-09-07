package com.civicpulse.backend_spring.exception;

/**
 * A request failed a rule that bean validation cannot express — an unknown
 * enum in a query parameter, an out-of-range filter, an empty PATCH body.
 * Rendered as 400 VALIDATION_ERROR.
 *
 * Messages name the field and the rule. They never echo the submitted value.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
