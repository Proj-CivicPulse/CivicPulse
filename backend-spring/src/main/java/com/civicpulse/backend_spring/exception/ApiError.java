package com.civicpulse.backend_spring.exception;

import lombok.Getter;

/**
 * The project-wide error wire format, identical across backend-spring and
 * backend-node (see docs/api-contract.md):
 *
 * <pre>{ "error": { "code": "UPPER_SNAKE_CASE", "message": "human readable" } }</pre>
 *
 * The frontend reads {@code error.message} in
 * frontend/src/services/api.ts — a flat body would silently degrade to a
 * generic "Request failed" message there.
 *
 * Never put stack traces, SQL, or internal details in {@code message};
 * those are logged server-side only.
 */
@Getter
public class ApiError {

    private final Detail error;

    public ApiError(String code, String message) {
        this.error = new Detail(code, message);
    }

    @Getter
    public static class Detail {
        private final String code;
        private final String message;

        Detail(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
