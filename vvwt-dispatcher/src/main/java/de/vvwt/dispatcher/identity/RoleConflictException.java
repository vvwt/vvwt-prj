package de.vvwt.dispatcher.identity;

/**
 * Thrown when a public key is submitted for a role that conflicts with an existing registration for
 * the same key bytes (AC4 of E01S06: different role → 409 Conflict).
 */
public class RoleConflictException extends RuntimeException {

    public RoleConflictException(String message) {
        super(message);
    }
}
