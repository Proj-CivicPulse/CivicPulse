package com.civicpulse.backend_spring.service.matching;

/**
 * backend-node could not be reached at all — connection refused, connect
 * timeout, unknown host, or a 5xx. A real outage: it counts against the circuit
 * breaker, and the caller falls back to the naive grouper.
 */
public class NodeConnectionException extends RuntimeException {

    public NodeConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
