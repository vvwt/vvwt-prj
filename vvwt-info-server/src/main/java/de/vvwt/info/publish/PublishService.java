// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish;

import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;

/**
 * Core service for tournament-registration + publisher endpoints (AC2–AC15).
 *
 * @see de.vvwt.info.publish.internal.DefaultPublishService
 * @see <a href="../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC2–AC15</a>
 */
public interface PublishService {

    // -------------------------------------------------------------------------
    // Typed exception hierarchy (defined on the interface for controller mapping)
    // -------------------------------------------------------------------------

    /** Invalid signature → 401. Audit-row written with signature_outcome=INVALID. */
    class SignatureInvalidException extends RuntimeException {
        public SignatureInvalidException() {
            super("Signature verification failed");
        }
    }

    /** Tenant not found in registry → 401 (signature cannot be verified). */
    class TenantNotFoundException extends RuntimeException {
        public TenantNotFoundException(String tenantId) {
            super("Tenant not registered: " + tenantId);
        }
    }

    /** seq != last_applied_seq + 1 → 409 FULL_RESYNC. */
    class SeqMismatchException extends RuntimeException {
        private final long lastAppliedSeq;
        private final String tournamentToken;

        public SeqMismatchException(long lastAppliedSeq, String tournamentToken) {
            super("Sequence mismatch — expected " + (lastAppliedSeq + 1));
            this.lastAppliedSeq = lastAppliedSeq;
            this.tournamentToken = tournamentToken;
        }

        public long getLastAppliedSeq() {
            return lastAppliedSeq;
        }

        public String getTournamentToken() {
            return tournamentToken;
        }
    }

    /** Tournament not found (no active tournament at this tenant/location/id) → 409 FULL_RESYNC. */
    class TournamentNotFoundException extends RuntimeException {
        public TournamentNotFoundException(String tournamentId) {
            super("Tournament not found: " + tournamentId);
        }
    }

    /** Request payload exceeds the configured maximum size → 413. */
    class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /** Malformed DomainEvent discriminator → 400. */
    class MalformedPayloadException extends RuntimeException {
        public MalformedPayloadException(String message) {
            super(message);
        }

        public MalformedPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // -------------------------------------------------------------------------
    // Service methods
    // -------------------------------------------------------------------------

    /**
     * Registers a tournament for the given tenant/location/tournament triple (AC2, AC6, AC7, AC12).
     *
     * @return {@link de.vvwt.info.dto.publish.TournamentRegistrationResponse} on success, or one of
     *     the typed exception subtypes
     */
    Object registerTournament(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            TournamentRegistrationRequest request,
            String base64Signature,
            String requestId,
            String sourceIp);

    /**
     * Accepts a signed delta event publish (AC3, AC4, AC7, AC10, AC11, AC13, AC14, AC15).
     *
     * @return {@code null} on success (200); typed exception on failure
     */
    Object publishDelta(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            long requestedSeq,
            String domainEventJson,
            String eventType,
            String base64Signature,
            String requestId,
            String sourceIp);

    /**
     * Accepts a signed full-resync snapshot (AC5, AC7, AC10, AC11, AC13, AC14).
     *
     * @return {@code null} on success (200); typed exception on failure
     */
    Object publishSnapshot(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            long snapshotSeq,
            String snapshotStateJson,
            String base64Signature,
            String requestId,
            String sourceIp);

    /**
     * Writes an audit-log entry for 5xx-durability (AC13).
     *
     * <p>The audit write commits in its own transaction even if the outer transaction rolls back.
     */
    void writeAuditLog(
            String tenantId,
            String tournamentId,
            String sourceIp,
            String requestId,
            int httpStatus,
            SignatureOutcome sigOutcome,
            RejectionReason rejectionReason,
            String requestPath);
}
