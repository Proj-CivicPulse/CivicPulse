package com.civicpulse.backend_spring.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * GET /incidents/{id}/grouping: why each report is part of this incident, in
 * an officer's terms — distance, wording, who decided — never a cosine score.
 */
@Getter
@AllArgsConstructor
public class GroupingDto {

    private final String incidentId;
    private final List<Member> members;

    /**
     * One report's latest grouping decision.
     *
     * <p>{@code kind}: {@code started} (it opened the incident), {@code joined}
     * (the matcher grouped it), {@code backup_rule} (the fallback grouped it
     * while text matching was down; it will be re-checked), {@code rechecked}
     * (moved here when re-checked), {@code merged} / {@code split} (an officer
     * did it), {@code unknown} (no decision on record).
     */
    @Getter
    @AllArgsConstructor
    public static class Member {
        private final String complaintId;
        private final String referenceNo;
        private final String kind;
        private final String explanation;
        /** The report it was compared with; null when there was none. */
        private final String siblingReferenceNo;
        /** Metres to that report; null without one or without coordinates. */
        private final Integer distanceM;
        private final String decidedAt;
    }
}
