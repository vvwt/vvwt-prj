package de.vvwt.slotopt.dispatcher.identity;

import java.util.UUID;

/**
 * Thrown when a client attempts to register a worker ID that is already registered under a
 * different role.
 *
 * <p>Per AC-ROLE-CONFLICT-EXCEPTION (E37S05): stores the conflicting workerId, the existing role,
 * and the requested role. Mapped to HTTP 409 Conflict by {@link KeyRegistrationController}.
 *
 * <p>Spec: E37S02 spec section (b) — role conflict → 409; DEC-6 (asymmetric-key registration
 * roles).
 *
 * <p>Story: E37S05
 */
public class RoleConflictException extends RuntimeException {

    private final UUID workerId;
    private final String existingRole;
    private final String requestedRole;

    /**
     * Constructs a new {@code RoleConflictException}.
     *
     * @param workerId the worker ID that has a role conflict
     * @param existingRole the role already registered for this worker ID
     * @param requestedRole the role the client is attempting to register as
     */
    public RoleConflictException(UUID workerId, String existingRole, String requestedRole) {
        super(
                "Worker ID "
                        + workerId
                        + " is already registered with role '"
                        + existingRole
                        + "'; cannot re-register as role '"
                        + requestedRole
                        + "'");
        this.workerId = workerId;
        this.existingRole = existingRole;
        this.requestedRole = requestedRole;
    }

    /** @return the worker ID involved in the conflict */
    public UUID getWorkerId() {
        return workerId;
    }

    /** @return the existing role already registered for this worker ID */
    public String getExistingRole() {
        return existingRole;
    }

    /** @return the role the client attempted to register as */
    public String getRequestedRole() {
        return requestedRole;
    }
}
