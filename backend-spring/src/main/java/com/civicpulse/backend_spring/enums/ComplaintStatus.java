package com.civicpulse.backend_spring.enums;

/**
 * Lifecycle of a single complaint. Same vocabulary, same meaning as
 * {@link IncidentStatus}: {@link #RESOLVED} means the problem was fixed,
 * {@link #CLOSED} means the complaint was terminated without a fix.
 */
public enum ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    /** Fixed. */
    RESOLVED,
    /** Terminated without a fix. Not a resolution. */
    CLOSED
}
