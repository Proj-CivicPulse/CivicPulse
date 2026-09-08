package com.civicpulse.backend_spring.service.matching;

/**
 * The trigger call to backend-node timed out. Node may still be working on it —
 * it takes the PROCESSING claim before doing anything slow — so this is <em>not</em>
 * on its own a circuit-breaker failure. The caller re-reads the complaint: a
 * PROCESSING/MATCHED status means Node has it and the late result will
 * self-correct; still PENDING means the request never landed and it is a real
 * failure after all.
 */
public class NodeSlowException extends RuntimeException {

    public NodeSlowException(String message, Throwable cause) {
        super(message, cause);
    }
}
