package de.vvwt.info.persistence.audit;

/**
 * Enumerates the application-level rejection reason stored in {@code audit_log.rejection_reason}.
 *
 * <p>Split per Brief D-X5: this column records WHY a request was rejected (application logic),
 * while {@link SignatureOutcome} records the cryptographic outcome. When a request is accepted,
 * {@code rejection_reason} is {@code null}.
 *
 * @see SignatureOutcome
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03</a>
 */
public enum RejectionReason {

    /** Submitted public key does not match the registered key for this tenant. */
    KEY_MISMATCH,

    /** Algorithm is past its deprecation date and no longer accepted for new registrations. */
    ALGORITHM_DEPRECATED,

    /** Algorithm identifier is not in the server's algorithm_registry. */
    ALGORITHM_UNKNOWN,

    /** Tenant count limit exceeded (e.g., self-host single-tenant enforcement). */
    TENANT_LIMIT_EXCEEDED,

    /** Invitation token is invalid, already consumed, or expired. */
    INVITATION_INVALID,

    /** Tournament delta sequence does not follow the expected monotonic order. */
    SEQ_MISMATCH,

    /** Tournament not found for the given tournament_token. */
    TOURNAMENT_NOT_FOUND,

    /** Request rate limit exceeded. */
    RATE_LIMITED,

    /** Request payload is syntactically malformed. */
    MALFORMED,

    /** Request payload exceeds the maximum allowed size. */
    PAYLOAD_TOO_LARGE,

    /** Server-side internal error prevented processing. */
    INTERNAL_ERROR
}
