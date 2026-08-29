package com.civicpulse.backend_spring.exception;

/**
 * A requested entity does not exist. Rendered as 404 NOT_FOUND.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
