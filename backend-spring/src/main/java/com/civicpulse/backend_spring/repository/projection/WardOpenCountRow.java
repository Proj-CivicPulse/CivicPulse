package com.civicpulse.backend_spring.repository.projection;

/**
 * One grouped count per ward. Keeps the public ward strip to a single query
 * rather than one count per ward.
 */
public interface WardOpenCountRow {

    Long getWardId();

    Long getOpenCount();
}
