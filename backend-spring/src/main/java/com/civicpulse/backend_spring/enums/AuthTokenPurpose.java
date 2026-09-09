package com.civicpulse.backend_spring.enums;

/**
 * What an emailed token entitles the bearer to do.
 *
 * Checked on redemption, not just on issue: without it a verification token —
 * the cheaper of the two to obtain — could be presented to the reset endpoint
 * and used to take over the account. Same reasoning as the {@code typ} claim on
 * JWTs in {@link com.civicpulse.backend_spring.service.auth.JwtService}.
 */
public enum AuthTokenPurpose {

    EMAIL_VERIFICATION,

    PASSWORD_RESET
}
