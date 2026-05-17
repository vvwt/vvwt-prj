// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish.internal;

import de.vvwt.info.crypto.SignatureVerifier;
import de.vvwt.info.crypto.SignatureVerifierRegistry;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.publish.TournamentRegistrationResponse;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.AuditLogRecord;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.persistence.tenant.TenantRecord;
import de.vvwt.info.persistence.tournament.TournamentDao;
import de.vvwt.info.persistence.tournament.TournamentDeltaDao;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import de.vvwt.info.publish.JcsCanonicalizer;
import de.vvwt.info.publish.PublishService;
import de.vvwt.info.publish.config.PublishProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service for tournament-registration + publisher endpoints (AC2–AC15).
 *
 * <p>Orchestrates all acceptance-criterion paths for:
 *
 * <ul>
 *   <li>{@link #registerTournament} — {@code POST /api/v1/tournaments/{t}/{l}/{id}/register} (AC2,
 *       AC6, AC7, AC11, AC12)
 *   <li>{@link #publishDelta} — {@code POST /api/v1/publish/{t}/{l}/{id}} (AC3, AC4, AC7, AC10,
 *       AC11, AC13)
 *   <li>{@link #publishSnapshot} — {@code POST /api/v1/publish/{t}/{l}/{id}/snapshot} (AC5, AC7,
 *       AC10, AC11, AC13)
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S05.story.md">E38S05
 *     AC2–AC15</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-6.md">DEC-6</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-46.md">DEC-46</a>
 */
public class DefaultPublishService implements PublishService {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final TenantDao tenantDao;
    private final TournamentDao tournamentDao;
    private final TournamentDeltaDao tournamentDeltaDao;
    private final AuditLogDao auditLogDao;
    private final SignatureVerifierRegistry signatureVerifierRegistry;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final PublishProperties publishProperties;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @SuppressWarnings("this-escape")
    public DefaultPublishService(
            TenantDao tenantDao,
            TournamentDao tournamentDao,
            TournamentDeltaDao tournamentDeltaDao,
            AuditLogDao auditLogDao,
            SignatureVerifierRegistry signatureVerifierRegistry,
            JcsCanonicalizer jcsCanonicalizer,
            PublishProperties publishProperties,
            Clock clock) {
        this.tenantDao = tenantDao;
        this.tournamentDao = tournamentDao;
        this.tournamentDeltaDao = tournamentDeltaDao;
        this.auditLogDao = auditLogDao;
        this.signatureVerifierRegistry = signatureVerifierRegistry;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.publishProperties = publishProperties;
        this.clock = clock;
        this.secureRandom = new SecureRandom();
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tournaments/{tenantId}/{locationId}/{tournamentId}/register (AC2, AC6, AC7)
    // -------------------------------------------------------------------------

    /**
     * Registers a tournament for the given tenant/location/tournament triple (AC2, AC6, AC7, AC12).
     *
     * <p>First registration: mints {@code tournament_token} (256-bit random Base64URL) + {@code
     * per_tournament_secret} (32 bytes random) and inserts a {@code tournament} row.
     *
     * <p>Re-registration (existing {@code (tenant, location, tournament_id)}): atomic supersede per
     * AC6 — prior row gets {@code superseded_at=now()}, prior deltas are deleted, new row inserted.
     *
     * @return {@link TournamentRegistrationResponse} on success, or one of the typed exception
     *     subtypes
     */
    @Override
    @Transactional
    public Object registerTournament(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            TournamentRegistrationRequest request,
            String base64Signature,
            String requestId,
            String sourceIp) {

        SignatureOutcome sigOutcome = SignatureOutcome.INVALID;
        RejectionReason rejectionReason = null;
        int httpStatus = 401;
        String auditTournamentId = tournamentId;

        try {
            // Step 1: Resolve tenant + verify signature (AC10, AC11)
            Optional<TenantRecord> tenantOpt = tenantDao.findById(tenantId);
            if (tenantOpt.isEmpty()) {
                rejectionReason = null; // signature not verifiable — treat as invalid sig
                return new SignatureInvalidException();
            }

            TenantRecord tenant = tenantOpt.get();
            byte[] payload = jcsCanonicalizer.canonicalize(envelopeJson);
            byte[] signature = Base64.getDecoder().decode(base64Signature);

            SignatureVerifier verifier = signatureVerifierRegistry.resolve(tenant.algorithmId());
            if (!verifier.verify(payload, signature, tenant.publicKey())) {
                return new SignatureInvalidException();
            }

            sigOutcome = SignatureOutcome.VALID;
            httpStatus = 200;

            // Step 2: Find existing active tournament for this (tenant, location)
            Optional<TenantRecord> defaultTenantOpt = tenantDao.findDefaultTenant();
            boolean isDefaultTenant =
                    defaultTenantOpt.isPresent()
                            && defaultTenantOpt.get().tenantId().equals(tenantId);

            // On-demand location creation (AC12): no extra step needed — tournament row stores
            // location_id directly. Default tenant is unlimited; no location-limit check here.

            Optional<TournamentRecord> existingActiveTournament =
                    tournamentDao.findActiveTournament(tenantId, locationId);

            if (existingActiveTournament.isPresent()) {
                // Atomic supersede (AC6)
                TournamentRecord prior = existingActiveTournament.get();
                TournamentRecord superseded =
                        new TournamentRecord(
                                prior.tournamentId(),
                                prior.tenantId(),
                                prior.locationId(),
                                prior.tournamentToken(),
                                prior.perTournamentSecret(),
                                prior.state(),
                                prior.lastAppliedSeq(),
                                prior.registeredAt(),
                                LocalDateTime.now(clock));
                tournamentDao.save(superseded);

                // Delete prior deltas (hard-delete per AC6 out-of-scope note)
                tournamentDeltaDao.deleteAllByTournamentId(prior.tournamentId());
            }

            // Generate new tournament_token + per_tournament_secret
            byte[] tokenBytes = new byte[32];
            secureRandom.nextBytes(tokenBytes);
            String tournamentToken =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

            byte[] secretBytes = new byte[32];
            secureRandom.nextBytes(secretBytes);

            TournamentRecord newTournament =
                    new TournamentRecord(
                                    tournamentId,
                                    tenantId,
                                    locationId,
                                    tournamentToken,
                                    secretBytes,
                                    null, // state: null before first snapshot
                                    0L, // last_applied_seq: 0 before any delta
                                    LocalDateTime.now(clock),
                                    null) // superseded_at: null (active)
                            .asNew(); // marks as INSERT for Spring Data JDBC
            tournamentDao.save(newTournament);

            return new TournamentRegistrationResponse(tournamentToken, secretBytes, "1.0");

        } catch (IllegalArgumentException e) {
            // Base64 decode failure or sig verification setup error
            sigOutcome = SignatureOutcome.INVALID;
            rejectionReason = null;
            httpStatus = 401;
            return new SignatureInvalidException();
        } finally {
            writeAuditLog(
                    tenantId,
                    auditTournamentId,
                    sourceIp,
                    requestId,
                    httpStatus,
                    sigOutcome,
                    rejectionReason,
                    "/api/v1/tournaments/"
                            + tenantId
                            + "/"
                            + locationId
                            + "/"
                            + tournamentId
                            + "/register");
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/publish/{tenantId}/{locationId}/{tournamentId} (AC3, AC4, AC13)
    // -------------------------------------------------------------------------

    /**
     * Accepts a signed delta event publish (AC3, AC4, AC7, AC10, AC11, AC13, AC14, AC15).
     *
     * @return {@code null} on success (200); typed exception on failure
     */
    @Override
    @Transactional
    public Object publishDelta(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            long requestedSeq,
            String domainEventJson,
            String eventType,
            String base64Signature,
            String requestId,
            String sourceIp) {

        SignatureOutcome sigOutcome = SignatureOutcome.INVALID;
        RejectionReason rejectionReason = null;
        int httpStatus = 401;
        String requestPath = "/api/v1/publish/" + tenantId + "/" + locationId + "/" + tournamentId;

        try {
            // Body-size guard (AC14)
            long bodySize = envelopeJson.getBytes(StandardCharsets.UTF_8).length;
            if (bodySize > publishProperties.getMaxDeltaBytes()) {
                sigOutcome = SignatureOutcome.NA;
                rejectionReason = RejectionReason.PAYLOAD_TOO_LARGE;
                httpStatus = 413;
                return new PayloadTooLargeException("Delta payload exceeds max-delta-bytes limit");
            }

            // Tenant + signature verification (AC10, AC11)
            Optional<TenantRecord> tenantOpt = tenantDao.findById(tenantId);
            if (tenantOpt.isEmpty()) {
                return new SignatureInvalidException();
            }

            TenantRecord tenant = tenantOpt.get();
            byte[] payload = jcsCanonicalizer.canonicalize(envelopeJson);
            byte[] signature = Base64.getDecoder().decode(base64Signature);

            SignatureVerifier verifier = signatureVerifierRegistry.resolve(tenant.algorithmId());
            if (!verifier.verify(payload, signature, tenant.publicKey())) {
                return new SignatureInvalidException();
            }

            sigOutcome = SignatureOutcome.VALID;

            // Tournament lookup (AC4 — seq=0 / unknown tournament → FULL_RESYNC)
            Optional<TournamentRecord> tournamentOpt = tournamentDao.findById(tournamentId);
            if (tournamentOpt.isEmpty()) {
                rejectionReason = RejectionReason.SEQ_MISMATCH;
                httpStatus = 409;
                return new TournamentNotFoundException(tournamentId);
            }

            TournamentRecord tournament = tournamentOpt.get();

            // Ensure tournament belongs to the given tenant/location
            if (!tournament.tenantId().equals(tenantId)
                    || !tournament.locationId().equals(locationId)) {
                rejectionReason = RejectionReason.TOURNAMENT_NOT_FOUND;
                httpStatus = 409;
                return new TournamentNotFoundException(tournamentId);
            }

            // Seq monotonicity check (AC4)
            long expectedSeq = tournament.lastAppliedSeq() + 1;
            if (requestedSeq != expectedSeq) {
                rejectionReason = RejectionReason.SEQ_MISMATCH;
                httpStatus = 409;
                return new SeqMismatchException(
                        tournament.lastAppliedSeq(), tournament.tournamentToken());
            }

            // Persist delta — use explicit INSERT to avoid Spring Data JDBC composite-key ambiguity
            tournamentDeltaDao.insertDelta(
                    tournamentId,
                    requestedSeq,
                    eventType,
                    domainEventJson,
                    LocalDateTime.now(clock));

            // Advance last_applied_seq
            TournamentRecord updated =
                    new TournamentRecord(
                            tournament.tournamentId(),
                            tournament.tenantId(),
                            tournament.locationId(),
                            tournament.tournamentToken(),
                            tournament.perTournamentSecret(),
                            tournament.state(),
                            requestedSeq,
                            tournament.registeredAt(),
                            tournament.supersededAt());
            tournamentDao.save(updated);

            httpStatus = 200;
            return null; // success

        } catch (IllegalArgumentException e) {
            sigOutcome = SignatureOutcome.INVALID;
            httpStatus = 401;
            return new SignatureInvalidException();
        } finally {
            writeAuditLog(
                    tenantId,
                    tournamentId,
                    sourceIp,
                    requestId,
                    httpStatus,
                    sigOutcome,
                    rejectionReason,
                    requestPath);
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/publish/{tenantId}/{locationId}/{tournamentId}/snapshot (AC5, AC13)
    // -------------------------------------------------------------------------

    /**
     * Accepts a signed full-resync snapshot (AC5, AC7, AC10, AC11, AC13, AC14).
     *
     * @return {@code null} on success (200); typed exception on failure
     */
    @Override
    @Transactional
    public Object publishSnapshot(
            String tenantId,
            String locationId,
            String tournamentId,
            String envelopeJson,
            long snapshotSeq,
            String snapshotStateJson,
            String base64Signature,
            String requestId,
            String sourceIp) {

        SignatureOutcome sigOutcome = SignatureOutcome.INVALID;
        RejectionReason rejectionReason = null;
        int httpStatus = 401;
        String requestPath =
                "/api/v1/publish/" + tenantId + "/" + locationId + "/" + tournamentId + "/snapshot";

        try {
            // Body-size guard (AC14)
            long bodySize = envelopeJson.getBytes(StandardCharsets.UTF_8).length;
            if (bodySize > publishProperties.getMaxSnapshotBytes()) {
                sigOutcome = SignatureOutcome.NA;
                rejectionReason = RejectionReason.PAYLOAD_TOO_LARGE;
                httpStatus = 413;
                return new PayloadTooLargeException(
                        "Snapshot payload exceeds max-snapshot-bytes limit");
            }

            // Tenant + signature verification (AC10, AC11)
            Optional<TenantRecord> tenantOpt = tenantDao.findById(tenantId);
            if (tenantOpt.isEmpty()) {
                return new SignatureInvalidException();
            }

            TenantRecord tenant = tenantOpt.get();
            byte[] payload = jcsCanonicalizer.canonicalize(envelopeJson);
            byte[] signature = Base64.getDecoder().decode(base64Signature);

            SignatureVerifier verifier = signatureVerifierRegistry.resolve(tenant.algorithmId());
            if (!verifier.verify(payload, signature, tenant.publicKey())) {
                return new SignatureInvalidException();
            }

            sigOutcome = SignatureOutcome.VALID;

            // Tournament lookup (AC5)
            Optional<TournamentRecord> tournamentOpt = tournamentDao.findById(tournamentId);
            if (tournamentOpt.isEmpty()) {
                rejectionReason = RejectionReason.TOURNAMENT_NOT_FOUND;
                httpStatus = 409;
                return new TournamentNotFoundException(tournamentId);
            }

            TournamentRecord tournament = tournamentOpt.get();

            if (!tournament.tenantId().equals(tenantId)
                    || !tournament.locationId().equals(locationId)) {
                rejectionReason = RejectionReason.TOURNAMENT_NOT_FOUND;
                httpStatus = 409;
                return new TournamentNotFoundException(tournamentId);
            }

            // Clear prior deltas (AC5: server clears prior tournament_delta history)
            tournamentDeltaDao.deleteAllByTournamentId(tournamentId);

            // Apply snapshot state and advance seq
            TournamentRecord updated =
                    new TournamentRecord(
                            tournament.tournamentId(),
                            tournament.tenantId(),
                            tournament.locationId(),
                            tournament.tournamentToken(),
                            tournament.perTournamentSecret(),
                            snapshotStateJson,
                            snapshotSeq,
                            tournament.registeredAt(),
                            tournament.supersededAt());
            tournamentDao.save(updated);

            httpStatus = 200;
            return null; // success

        } catch (IllegalArgumentException e) {
            sigOutcome = SignatureOutcome.INVALID;
            httpStatus = 401;
            return new SignatureInvalidException();
        } finally {
            writeAuditLog(
                    tenantId,
                    tournamentId,
                    sourceIp,
                    requestId,
                    httpStatus,
                    sigOutcome,
                    rejectionReason,
                    requestPath);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Writes an audit-log entry with {@code Propagation.REQUIRES_NEW} for 5xx-durability (AC13).
     *
     * <p>The audit write commits in its own transaction even if the outer transaction rolls back.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAuditLog(
            String tenantId,
            String tournamentId,
            String sourceIp,
            String requestId,
            int httpStatus,
            SignatureOutcome sigOutcome,
            RejectionReason rejectionReason,
            String requestPath) {
        AuditLogRecord record =
                new AuditLogRecord(
                        null,
                        requestId,
                        sourceIp != null ? sourceIp : "unknown",
                        LocalDateTime.now(clock),
                        sigOutcome,
                        rejectionReason,
                        httpStatus,
                        tenantId,
                        tournamentId,
                        requestPath);
        auditLogDao.append(record);
    }
}
