package com.civicpulse.backend_spring.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "jwt")
@Validated
@Getter
@Setter
public class JwtProperties {

    /**
     * HMAC-SHA signing key.
     *
     * The 32-character floor is enforced here so a weak key fails at
     * startup with a readable message. Without it, jjwt throws
     * WeakKeyException deep inside the first login attempt instead — a
     * confusing 500 that looks like a bug in the login flow.
     *
     * The value is never logged; {@code toString()} is not generated for
     * this class beyond Lombok's getters, and nothing prints it.
     */
    @NotBlank(message = "jwt.secret is required (see .env.example)")
    @Size(min = 32, message = "jwt.secret must be at least 32 characters (see .env.example)")
    private String secret;

    /** Access-token lifetime in milliseconds. Short by design. */
    @Positive
    private long accessExpiration;

    /** Refresh-token lifetime in milliseconds. */
    @Positive
    private long refreshExpiration;
}
