package com.civicpulse.backend_spring.dto.ward;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Ward name plus a count of currently-open incidents. Nothing else, ever.
 *
 * This shape is served unauthenticated to the public landing page, so it is
 * kept deliberately narrow and is reused rather than re-derived — the officer
 * dashboard's ward breakdown uses this same class, which makes it provable
 * that the public endpoint cannot drift into carrying more.
 *
 * See WardController#summary for what must never be added here and why.
 */
@Getter
@AllArgsConstructor
public class WardSummaryDto {

    private final String wardId;
    private final String wardName;
    private final long openIncidentCount;
}
