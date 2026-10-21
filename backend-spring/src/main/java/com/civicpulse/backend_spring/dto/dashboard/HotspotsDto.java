package com.civicpulse.backend_spring.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Hotspots (GET /dashboard/hotspots, docs/roadmap.md Phase 9): places where
 * several active incidents of one category sit close together.
 *
 * <p>The definition travels with the answer, so the UI can say exactly what
 * a hotspot is ("3+ active incidents within 500 m, reported since …") rather
 * than restating constants it might get wrong.
 */
@Getter
@AllArgsConstructor
public class HotspotsDto {

    private final Definition definition;
    /** Highest-priority first. */
    private final List<Hotspot> hotspots;

    @Getter
    @AllArgsConstructor
    public static class Definition {
        private final int minIncidents;
        private final double radiusMetres;
        /** The report window applied: [from, to). {@code to} is null when open-ended. */
        private final String from;
        private final String to;
    }

    @Getter
    @AllArgsConstructor
    public static class Hotspot {
        /** {@code <category>-<lowest member incident id>}: stable while its members are. */
        private final String id;
        private final String category;
        private final int incidentCount;
        private final long complaintCount;
        /** Mean of the member incidents' centres. */
        private final double latitude;
        private final double longitude;
        /** Distance from the centre to the farthest member, in metres. */
        private final double radiusMetres;
        private final double maxPriorityScore;
        private final String maxPriorityBand;
        private final String lastReportedAt;
        private final List<WardRef> wards;
        /** Highest-priority first. */
        private final List<Member> incidents;
    }

    @Getter
    @AllArgsConstructor
    public static class WardRef {
        private final String id;
        private final String name;
    }

    @Getter
    @AllArgsConstructor
    public static class Member {
        private final String id;
        private final String wardId;
        private final double priorityScore;
        private final String priorityBand;
        private final int complaintCount;
        private final double latitude;
        private final double longitude;
        private final String address;
        private final String lastReportedAt;
    }
}
