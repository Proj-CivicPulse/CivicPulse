package com.civicpulse.backend_spring.enums;

/**
 * Which matcher produced an incident assignment.
 *
 * <p>{@code SEMANTIC} is backend-node's embedding pipeline; {@code NAIVE} is the
 * {@code NaiveIncidentGrouper} fallback that runs when Node is unreachable.
 */
public enum Matcher {
    SEMANTIC,
    NAIVE
}
