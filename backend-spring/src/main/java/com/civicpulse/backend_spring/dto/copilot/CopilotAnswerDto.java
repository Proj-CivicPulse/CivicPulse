package com.civicpulse.backend_spring.dto.copilot;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * The Copilot's reply (docs/api-contract.md, "Copilot").
 *
 * {@code sources} are only citations Spring itself put in the grounding packet;
 * anything else the model named is dropped and counted in
 * {@code droppedCitations}. {@code ungrounded} is true when an answer was given
 * but nothing valid supports it — the UI must say so rather than present it as
 * fact.
 */
@Getter
@AllArgsConstructor
public class CopilotAnswerDto {

    private final String answer;
    /** false when the data in scope could not answer the question. */
    private final boolean answerable;
    /** true when the model declined; {@code answer} then says so. */
    private final boolean refused;
    private final boolean ungrounded;
    private final int droppedCitations;
    private final List<Source> sources;
    private final Scope scope;
    private final String model;
    private final String generatedAt;

    /**
     * One citation. {@code kind} is incident | ward | category | metric;
     * {@code targetId} is what the UI navigates with (incident id, ward id,
     * category code) and is null for metrics.
     */
    @Getter
    @AllArgsConstructor
    public static class Source {
        private final String id;
        private final String kind;
        private final String label;
        private final String targetId;
    }

    /** What the answer was about, so the UI can show "Answering for: Ward 48, last 30 days". */
    @Getter
    @AllArgsConstructor
    public static class Scope {
        private final String wardId;
        private final String wardName;
        private final String category;
        private final String categoryName;
        private final String from;
        private final String to;
    }
}
