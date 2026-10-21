package com.civicpulse.backend_spring.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A Copilot failure that the officer should see as a clear state rather than
 * a generic error: not configured (503), provider busy (503), or the answer
 * could not be produced (502). Codes are in {@link ErrorCode}.
 */
@Getter
public class CopilotException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CopilotException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static CopilotException unavailable() {
        return new CopilotException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.COPILOT_UNAVAILABLE,
                "The Copilot is not configured on this server");
    }

    public static CopilotException busy() {
        return new CopilotException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.COPILOT_BUSY,
                "The Copilot is busy — try again in a minute");
    }

    public static CopilotException upstream(String message) {
        return new CopilotException(HttpStatus.BAD_GATEWAY, ErrorCode.COPILOT_UPSTREAM_ERROR, message);
    }
}
