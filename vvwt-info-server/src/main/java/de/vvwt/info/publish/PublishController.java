// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.error.ErrorResponse;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.publish.TournamentRegistrationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for publisher endpoints (AC7).
 *
 * <p>Endpoints (AC7):
 *
 * <ul>
 *   <li>{@code POST /api/v1/tournaments/{tenant_id}/{location_id}/{tournament_id}/register} —
 *       tournament-registration (AC2, AC6, AC12).
 *   <li>{@code POST /api/v1/publish/{tenant_id}/{location_id}/{tournament_id}} — delta publish
 *       (AC3, AC4, AC14, AC15).
 *   <li>{@code POST /api/v1/publish/{tenant_id}/{location_id}/{tournament_id}/snapshot} — snapshot
 *       resync (AC5, AC14).
 * </ul>
 *
 * <p>Signature header: {@code X-Vvwt-Signature: <base64-encoded signature>} (AC8).
 *
 * <p>Correlation header: optional {@code X-Request-Id} for audit-log cross-referencing.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S05.story.md">E38S05 AC7</a>
 */
@RestController
public class PublishController {

    private final PublishService publishService;
    private final ObjectMapper objectMapper;

    public PublishController(PublishService publishService, ObjectMapper objectMapper) {
        this.publishService = publishService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tournaments/{tenant_id}/{location_id}/{tournament_id}/register (AC7)
    // -------------------------------------------------------------------------

    /**
     * Tournament-registration endpoint (AC2, AC7, AC12).
     *
     * <p>Returns 200 + {@code Envelope<TournamentRegistrationResponse>} on success.
     *
     * <p>Returns 401 when signature is invalid (AC10, AC11).
     */
    @PostMapping("/api/v1/tournaments/{tenantId}/{locationId}/{tournamentId}/register")
    public ResponseEntity<Envelope<?>> registerTournament(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("locationId") String locationId,
            @PathVariable("tournamentId") String tournamentId,
            @RequestHeader("X-Vvwt-Signature") String signature,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody Envelope<@Valid TournamentRegistrationRequest> envelope)
            throws JsonProcessingException {

        String envelopeJson = objectMapper.writeValueAsString(envelope.payload());
        String sourceIp = "unknown"; // TODO E38S07: extract from trusted proxy headers

        Object result =
                publishService.registerTournament(
                        tenantId,
                        locationId,
                        tournamentId,
                        envelopeJson,
                        envelope.payload(),
                        signature,
                        requestId,
                        sourceIp);

        return switch (result) {
            case TournamentRegistrationResponse response ->
                    ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, response));
            case PublishService.SignatureInvalidException e ->
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.SignatureInvalid()));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "INTERNAL_ERROR")));
        };
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/publish/{tenant_id}/{location_id}/{tournament_id} (AC7)
    // -------------------------------------------------------------------------

    /**
     * Delta publish endpoint (AC3, AC4, AC7, AC14, AC15).
     *
     * <p>Returns 200 on success, 409 on seq mismatch, 401 on invalid signature, 413 on oversize
     * body, 400 on malformed event.
     */
    @PostMapping("/api/v1/publish/{tenantId}/{locationId}/{tournamentId}")
    public ResponseEntity<Envelope<?>> publishDelta(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("locationId") String locationId,
            @PathVariable("tournamentId") String tournamentId,
            @RequestParam("seq") long seq,
            @RequestHeader("X-Vvwt-Signature") String signature,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody String rawBody)
            throws JsonProcessingException {

        String sourceIp = "unknown"; // TODO E38S07

        // Validate and extract event type from raw JSON (AC15 — malformed discriminator → 400)
        String eventType;
        String domainEventJson;
        try {
            Envelope<?> envelope = objectMapper.readValue(rawBody, Envelope.class);
            JsonNode payloadNode = objectMapper.valueToTree(envelope.payload());
            JsonNode typeNode = payloadNode.get("type");
            if (typeNode == null || typeNode.isNull()) {
                return ResponseEntity.badRequest()
                        .body(
                                new Envelope<>(
                                        Envelope.SCHEMA_VERSION,
                                        new ErrorResponse.RegistrationRejected("MALFORMED")));
            }
            eventType = typeNode.asText();
            domainEventJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest()
                    .body(
                            new Envelope<>(
                                    Envelope.SCHEMA_VERSION,
                                    new ErrorResponse.RegistrationRejected("MALFORMED")));
        }

        Object result =
                publishService.publishDelta(
                        tenantId,
                        locationId,
                        tournamentId,
                        rawBody,
                        seq,
                        domainEventJson,
                        eventType,
                        signature,
                        requestId,
                        sourceIp);

        return switch (result) {
            case null -> ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, (Object) null));
            case PublishService.SignatureInvalidException e ->
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.SignatureInvalid()));
            case PublishService.SeqMismatchException e ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            ErrorResponse.FullResyncRequired.of(
                                                    e.getLastAppliedSeq(),
                                                    e.getTournamentToken())));
            case PublishService.TournamentNotFoundException e ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            ErrorResponse.FullResyncRequired.unknown()));
            case PublishService.PayloadTooLargeException e ->
                    ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "PAYLOAD_TOO_LARGE")));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "INTERNAL_ERROR")));
        };
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/publish/{tenant_id}/{location_id}/{tournament_id}/snapshot (AC7)
    // -------------------------------------------------------------------------

    /**
     * Snapshot resync endpoint (AC5, AC7, AC14).
     *
     * <p>Returns 200 on success, 401 on invalid signature, 409 on missing tournament, 413 on
     * oversize body.
     */
    @PostMapping("/api/v1/publish/{tenantId}/{locationId}/{tournamentId}/snapshot")
    public ResponseEntity<Envelope<?>> publishSnapshot(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("locationId") String locationId,
            @PathVariable("tournamentId") String tournamentId,
            @RequestHeader("X-Vvwt-Signature") String signature,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody String rawBody)
            throws JsonProcessingException {

        String sourceIp = "unknown"; // TODO E38S07

        // Extract snapshot payload
        long snapshotSeq;
        String snapshotStateJson;
        try {
            Envelope<?> envelope = objectMapper.readValue(rawBody, Envelope.class);
            JsonNode payloadNode = objectMapper.valueToTree(envelope.payload());
            JsonNode seqNode = payloadNode.get("sequenceNumber");
            snapshotSeq = seqNode != null ? seqNode.asLong(0L) : 0L;
            snapshotStateJson = objectMapper.writeValueAsString(envelope.payload());
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest()
                    .body(
                            new Envelope<>(
                                    Envelope.SCHEMA_VERSION,
                                    new ErrorResponse.RegistrationRejected("MALFORMED")));
        }

        Object result =
                publishService.publishSnapshot(
                        tenantId,
                        locationId,
                        tournamentId,
                        rawBody,
                        snapshotSeq,
                        snapshotStateJson,
                        signature,
                        requestId,
                        sourceIp);

        return switch (result) {
            case null -> ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, (Object) null));
            case PublishService.SignatureInvalidException e ->
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.SignatureInvalid()));
            case PublishService.TournamentNotFoundException e ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            ErrorResponse.FullResyncRequired.unknown()));
            case PublishService.PayloadTooLargeException e ->
                    ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "PAYLOAD_TOO_LARGE")));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "INTERNAL_ERROR")));
        };
    }
}
