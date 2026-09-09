package com.civicpulse.backend_spring.enums;

/**
 * Why a refresh token stopped being usable. Stored so an operator can tell a
 * routine rotation apart from a security event when reading the table.
 */
public enum RevocationReason {

    /** Normal rotation: the holder exchanged it and got a successor. */
    ROTATED,

    /** The user signed out of this one session. */
    LOGOUT,

    /** The user signed out of every session at once. */
    LOGOUT_ALL,

    /**
     * An already-rotated token was presented again outside the race grace
     * window. Treated as a stolen token, so the entire session is killed.
     */
    REUSE_DETECTED,

    /**
     * The password was changed through a reset. Every existing session dies:
     * the usual reason for resetting is that someone else may be signed in,
     * and leaving their session alive would defeat the whole exercise.
     */
    PASSWORD_RESET
}
