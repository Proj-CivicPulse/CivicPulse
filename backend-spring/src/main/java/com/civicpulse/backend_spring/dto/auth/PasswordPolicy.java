package com.civicpulse.backend_spring.dto.auth;

/**
 * The one password rule, shared by registration and reset.
 *
 * Extracted because two copies WILL diverge, and the direction that matters is
 * the dangerous one: a reset path with a weaker rule than registration lets
 * anyone downgrade their own password below the policy, which quietly makes the
 * policy optional for every account that has ever reset.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    /** Upper bound guards against a multi-megabyte body being fed to BCrypt. */
    public static final int MAX_LENGTH = 100;

    public static final String PATTERN =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&+=!]).{8,}$";

    public static final String MESSAGE =
            "Password must contain at least 8 characters, one lowercase letter, "
                    + "one uppercase letter, one number, and one special character";

    private PasswordPolicy() {
    }
}
