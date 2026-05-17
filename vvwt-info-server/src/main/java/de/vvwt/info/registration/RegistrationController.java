// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.registration;

import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.error.ErrorResponse;
import de.vvwt.info.dto.registration.AlgorithmDescriptor;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for tenant-registration endpoints (AC5).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/register/algorithms} — returns {@code
 *       Envelope<List<AlgorithmDescriptor>>}
 *   <li>{@code POST /api/v1/register} — accepts {@code Envelope<RegistrationRequest>}, returns
 *       {@code Envelope<RegistrationResponse>} on success or {@code Envelope<RegistrationRejected>}
 *       on rejection
 * </ul>
 *
 * <p>Tenant identification: {@code X-Tenant-Id} header (Phase 1 — no session/auth layer yet).
 *
 * <p>Request correlation: optional {@code X-Request-Id} header for audit-log cross-referencing.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC5</a>
 */
@RestController
@RequestMapping("/api/v1/register")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Returns the server's supported signature algorithms (AC5).
     *
     * <p>Returns all active algorithms from {@code algorithm_registry}, ordered by {@code
     * algorithm_id} per AC5.
     *
     * @return 200 OK with {@code Envelope<List<AlgorithmDescriptor>>}
     */
    @GetMapping("/algorithms")
    public ResponseEntity<Envelope<List<AlgorithmDescriptor>>> listAlgorithms() {
        List<AlgorithmDescriptor> algorithms = registrationService.listAlgorithms();
        return ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, algorithms));
    }

    /**
     * Registers a tenant (AC5). First registration is unsigned per Brief D-X4(b) / DEC-42 D3.
     *
     * <p>HTTP status mapping per AC3:
     *
     * <ul>
     *   <li>200 OK — registration accepted
     *   <li>400 Bad Request — unknown algorithm (ALGORITHM_UNKNOWN) or malformed body
     *   <li>403 Forbidden — tenant limit exceeded (TENANT_LIMIT_EXCEEDED) or invalid invitation
     *       token (INVITATION_INVALID)
     *   <li>409 Conflict — public key mismatch for existing tenant (KEY_MISMATCH)
     *   <li>410 Gone — algorithm deprecated (ALGORITHM_DEPRECATED)
     * </ul>
     *
     * @param tenantId the tenant identifier from {@code X-Tenant-Id} header
     * @param requestId optional correlation ID from {@code X-Request-Id} header for audit
     * @param envelope the registration request envelope
     * @return response envelope with registration outcome or rejection reason
     */
    @PostMapping
    public ResponseEntity<Envelope<?>> register(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody Envelope<@Valid RegistrationRequest> envelope) {

        RegistrationRequest request = envelope.payload();
        // Source IP not available in MockMvc without additional config — use "unknown" as default
        String sourceIp = "unknown"; // TODO E38S07: extract from trusted proxy headers

        Object result = registrationService.register(tenantId, request, sourceIp, requestId);

        return switch (result) {
            case RegistrationResponse response ->
                    ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, response));

            case RegistrationService.AlgorithmUnknownException e ->
                    ResponseEntity.badRequest()
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "ALGORITHM_UNKNOWN")));

            case RegistrationService.AlgorithmDeprecatedException e ->
                    ResponseEntity.status(HttpStatus.GONE)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "ALGORITHM_DEPRECATED")));

            case RegistrationService.KeyMismatchException e ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "KEY_MISMATCH")));

            case RegistrationService.TenantLimitExceededException e ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "TENANT_LIMIT_EXCEEDED")));

            case RegistrationService.InvitationInvalidException e ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(
                                    new Envelope<>(
                                            Envelope.SCHEMA_VERSION,
                                            new ErrorResponse.RegistrationRejected(
                                                    "INVITATION_INVALID")));

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
