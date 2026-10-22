package com.civicpulse.backend_spring.dto.incident;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The result of a merge or unlink: both incidents as they now are.
 * {@code source} is null after a merge, which deletes the emptied incident.
 */
@Getter
@AllArgsConstructor
public class MembershipChangeDto {

    private final IncidentDto source;
    private final IncidentDto target;
}
