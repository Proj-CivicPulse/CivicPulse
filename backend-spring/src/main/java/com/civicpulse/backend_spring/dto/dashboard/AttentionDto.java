package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * "Needs attention" (GET /dashboard/attention, docs/roadmap.md Phase 10).
 *
 * <p>Statements, not counts alone — and every statement carries the incidents
 * behind it, because an insight an officer cannot open is a claim they cannot
 * check. Signals always come back in the same order, empty ones included, so
 * "0 worsening" is visibly an answer rather than a missing card.
 */
@Getter
@AllArgsConstructor
public class AttentionDto {

    /** The instant every signal was evaluated at. */
    private final String asOf;
    /** high_priority, worsening, in_hotspot, category_spike — in that order. */
    private final List<Signal> signals;

    @Getter
    @AllArgsConstructor
    public static class Signal {
        private final String key;
        /** The rule in words, for the UI to show beside the number. */
        private final String definition;
        /** Highest priority first. */
        private final List<Item> incidents;
        /** category_spike only; empty otherwise. */
        private final List<Spike> categories;
    }

    @Getter
    @AllArgsConstructor
    public static class Item {
        private final String id;
        private final String wardId;
        private final String wardName;
        private final String category;
        private final String address;
        private final double priorityScore;
        private final String priorityBand;
        private final int complaintCount;
        /** Member reports in the last 72 hours. */
        private final long recentReports;
        private final String lastReportedAt;
    }

    @Getter
    @AllArgsConstructor
    public static class Spike {
        private final String category;
        private final long lastWeek;
        /** Mean reports per week over the four weeks before. */
        private final double priorWeeklyMean;
    }
}
