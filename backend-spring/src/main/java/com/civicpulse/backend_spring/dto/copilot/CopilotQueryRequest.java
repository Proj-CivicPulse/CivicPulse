package com.civicpulse.backend_spring.dto.copilot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /copilot/query. The scope fields are the officer's current Explorer
 * filters, so the Copilot answers about the view they are looking at. All
 * optional: no scope means the whole city, all time.
 *
 * There is no officer id here on purpose — who is asking comes from the
 * session, never from the body.
 */
@Getter
@Setter
public class CopilotQueryRequest {

    @NotBlank
    @Size(max = 1000)
    private String question;

    /** Internal ward id, as on the wire. */
    private String wardId;

    /** Category code; any registry alias is canonicalised. */
    private String category;

    /** ISO-8601 instants bounding reported_at, [from, to). */
    private String from;
    private String to;
}
