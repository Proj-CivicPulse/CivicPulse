package com.civicpulse.backend_spring.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Non-secret application settings. Validated at startup so a
 * misconfiguration fails immediately instead of at the first request.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Validated
@Getter
@Setter
public class AppProperties {

    /** The one browser origin allowed by CORS. Never a wildcard. */
    @NotBlank
    private String frontendOrigin;

    /** Whether auth cookies carry the Secure flag. Must be true over HTTPS. */
    private boolean cookieSecure = false;

    /**
     * Hard ceiling on the /health database probe, in seconds. Default
     * accommodates a Neon cold start; lower it for a local database.
     */
    @Positive
    private long healthTimeoutSeconds = 10;
}
