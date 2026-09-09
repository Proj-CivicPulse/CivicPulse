/**
 * The client-side mirror of the server's password rule.
 *
 * Kept in one place for the same reason the server keeps
 * `dto/auth/PasswordPolicy.java` in one place: registration and reset both
 * enforce it, and two copies drift. The dangerous direction is a reset path
 * that accepts weaker passwords than registration, which would quietly make
 * the policy optional for anyone who has ever reset.
 *
 * This is convenience, not enforcement — it turns a 400 round-trip into
 * guidance the user can act on while typing. The server validates regardless.
 */

export const SPECIAL_CHARS = '@#$%^&+=!';

export const PASSWORD_HELPER =
    `At least 8 characters, with an uppercase and a lowercase letter, a number, and one of ${SPECIAL_CHARS}`;

/** Returns the first unmet rule, or null when the password is acceptable. */
export function passwordProblem(password: string): string | null {
    if (password.length < 8) return 'Use at least 8 characters.';
    if (password.length > 100) return 'Use 100 characters or fewer.';
    if (!/[a-z]/.test(password)) return 'Include a lowercase letter.';
    if (!/[A-Z]/.test(password)) return 'Include an uppercase letter.';
    if (!/\d/.test(password)) return 'Include a number.';
    if (!/[@#$%^&+=!]/.test(password)) return `Include one of ${SPECIAL_CHARS}`;
    return null;
}
