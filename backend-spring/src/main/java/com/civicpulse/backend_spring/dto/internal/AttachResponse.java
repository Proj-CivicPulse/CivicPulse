package com.civicpulse.backend_spring.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AttachResponse {

    private final String incidentId;
    private final boolean created;
    private final Double priorityScore;
    /** The complaint's terminal matching status after this attach: {@code matched} or {@code degraded}. */
    private final String matchingStatus;
}
