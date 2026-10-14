package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Complaint counts over time (GET /dashboard/trends, N3).
 *
 * Every bucket in the requested range is present in every series, zeros
 * included, so a chart never interpolates across a gap it cannot see.
 */
@Getter
@AllArgsConstructor
public class TrendDto {

    /** "day" or "week". Weeks start Monday 00:00 UTC. */
    private final String bucket;
    private final List<Series> series;

    @Getter
    @AllArgsConstructor
    public static class Series {
        /** Canonical category code when grouped by category; null for the single ungrouped series. */
        private final String key;
        private final List<Point> points;
    }

    @Getter
    @AllArgsConstructor
    public static class Point {
        private final String start;
        private final long count;
    }
}
