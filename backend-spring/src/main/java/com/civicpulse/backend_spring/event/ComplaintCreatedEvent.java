package com.civicpulse.backend_spring.event;

/**
 * Published by {@code ComplaintService} once a complaint is persisted. A
 * listener picks it up {@code AFTER_COMMIT} and fires the async matching
 * trigger to backend-node — so the citizen's response never waits on an
 * embedding call, and a rolled-back submission never triggers matching.
 */
public record ComplaintCreatedEvent(Long complaintId) {
}
