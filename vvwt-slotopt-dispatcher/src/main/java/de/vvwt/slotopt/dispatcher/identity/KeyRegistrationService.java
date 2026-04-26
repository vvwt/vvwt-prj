package de.vvwt.slotopt.dispatcher.identity;

/**
 * Service interface for key registration operations.
 *
 * <p>Per DEC-35: this interface lives in the public {@code identity} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.identity.internal.DefaultKeyRegistrationService}) lives in
 * {@code identity.internal}.
 *
 * <p>All consumers (controllers, tests) type their dependency as {@code KeyRegistrationService},
 * never as the implementation class (DEC-36).
 *
 * <p>Story: E37S05; AC-KEY-REGISTRATION-SERVICE
 */
public interface KeyRegistrationService {

    /**
     * Registers a public key with the dispatcher identity registry.
     *
     * <p>Behavior per AC-KEY-REGISTRATION-SERVICE:
     *
     * <ol>
     *   <li>Validates {@code request.algorithm()} against the {@code SignatureVerifierRegistry}.
     *       Unknown algorithm → {@link IllegalArgumentException} (→ HTTP 400).
     *   <li>Validates {@code request.publicKeyBytes()} length against the verifier's declared
     *       constraints. Out-of-range → {@link IllegalArgumentException} (→ HTTP 400).
     *   <li>Checks worker-ID conflict: same worker ID + different role → {@link
     *       de.vvwt.slotopt.dispatcher.identity.RoleConflictException} (→ HTTP 409). Same worker ID
     *       + same role + same algorithm → idempotent (returns existing registration).
     *   <li>Persists the new registration and returns the response.
     * </ol>
     *
     * @param request the registration request; must not be {@code null}
     * @return the registration response with the worker ID, role, algorithm, and registration
     *     timestamp
     * @throws IllegalArgumentException if the algorithm is unknown/null/empty, or if the public key
     *     length is outside the verifier's declared range
     * @throws de.vvwt.slotopt.dispatcher.identity.RoleConflictException if the worker ID is already
     *     registered with a different role
     */
    RegistrationOutcome register(RegisterKeyRequest request);
}
