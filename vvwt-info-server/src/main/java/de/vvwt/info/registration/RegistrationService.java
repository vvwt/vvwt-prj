package de.vvwt.info.registration;

import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import java.util.List;

/**
 * Core service for tenant-registration logic (AC5, AC8–AC14).
 *
 * @see de.vvwt.info.registration.internal.DefaultRegistrationService
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC5,
 *     AC8–AC14</a>
 */
public interface RegistrationService {

    // -------------------------------------------------------------------------
    // Typed exception hierarchy (defined on the interface for controller mapping)
    // -------------------------------------------------------------------------

    /**
     * Algorithm not found in registry → 400 Bad Request (AC3 ALGORITHM_UNKNOWN, RFC 7231 §6.5.1)
     */
    class AlgorithmUnknownException extends RuntimeException {
        public AlgorithmUnknownException(String algorithmId) {
            super("Algorithm not in registry: " + algorithmId);
        }
    }

    /** Algorithm past DEC-48 deprecation boundary → 410 Gone (AC3 ALGORITHM_DEPRECATED) */
    class AlgorithmDeprecatedException extends RuntimeException {
        public AlgorithmDeprecatedException(String algorithmId) {
            super("Algorithm deprecated: " + algorithmId);
        }
    }

    /** Second public key for same tenant → 409 Conflict (AC8 KEY_MISMATCH) */
    class KeyMismatchException extends RuntimeException {
        public KeyMismatchException(String tenantId) {
            super("Public key mismatch for tenant: " + tenantId);
        }
    }

    /** Max-tenants exceeded (self-host single-tenant default) → 403 (AC9 TENANT_LIMIT_EXCEEDED) */
    class TenantLimitExceededException extends RuntimeException {
        public TenantLimitExceededException() {
            super("Tenant registration limit exceeded");
        }
    }

    /** Invitation token missing or not in pool → 403 (AC10 INVITATION_INVALID) */
    class InvitationInvalidException extends RuntimeException {
        public InvitationInvalidException() {
            super("Invitation token is missing or already consumed");
        }
    }

    // -------------------------------------------------------------------------
    // Service methods
    // -------------------------------------------------------------------------

    /**
     * Returns all active algorithms from the registry, ordered by {@code algorithm_id} (AC5).
     *
     * @return ordered list of {@link AlgorithmDescriptor} for active algorithms
     */
    List<AlgorithmDescriptor> listAlgorithms();

    /**
     * Processes a tenant registration request.
     *
     * @param tenantId the tenant identifier from the request context
     * @param request the registration request body
     * @param sourceIp client IP address for audit log
     * @param requestId optional correlation ID for audit log
     * @return {@link RegistrationResponse} on success, or a typed rejection exception instance
     */
    Object register(
            String tenantId, RegistrationRequest request, String sourceIp, String requestId);
}
