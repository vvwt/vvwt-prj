// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the pull-packet endpoint.
 *
 * <p>Handles {@code POST /api/pull-packet}. Accepts JSON {@link PullPacketRequest}, validates
 * capability field, delegates to {@link PullPacketService}, and returns JSON {@link
 * PullPacketResponse} or HTTP 204 No Content.
 *
 * <p>HTTP status codes per AC-PULL-PACKET-CONTROLLER + spec section (b) error matrix:
 *
 * <ul>
 *   <li>200 OK — packet claimed ({@link PullPacketResponse})
 *   <li>204 No Content — no unclaimed packets available
 *   <li>400 Bad Request — {@code supportedAlgorithms} is empty array {@code []}
 *   <li>404 Not Found — {@code workerId} not registered ({@link WorkerNotFoundException})
 * </ul>
 *
 * <p>AC-CAPABILITY-FIELD-OPTIONAL (Brief D-2): missing {@code supportedAlgorithms} → defaults to
 * {@code ["Ed25519"]}. Empty array {@code []} → HTTP 400.
 *
 * <p>Story: E37S08; AC-PULL-PACKET-CONTROLLER; DEC-35
 */
@RestController
public class PullPacketController {

    private static final List<String> DEFAULT_ALGORITHMS = List.of("Ed25519");

    private final PullPacketService pullPacketService;

    public PullPacketController(PullPacketService pullPacketService) {
        this.pullPacketService = pullPacketService;
    }

    /**
     * Handles a pull-packet request from an authenticated worker.
     *
     * @param request the pull-packet request body
     * @return 200 with {@link PullPacketResponse} on success, 204 if no packets, 400/404 on error
     */
    @PostMapping("/api/pull-packet")
    public ResponseEntity<?> pullPacket(@RequestBody PullPacketRequest request) {
        // Resolve supportedAlgorithms: absent → default; empty → 400
        List<String> algorithms;
        if (!request.isSupportedAlgorithmsPresent() || request.getSupportedAlgorithms() == null) {
            // Absent field: default to ["Ed25519"] per AC-CAPABILITY-FIELD-OPTIONAL
            algorithms = DEFAULT_ALGORITHMS;
        } else if (request.getSupportedAlgorithms().isEmpty()) {
            // Empty array []: 400 per AC-CAPABILITY-FIELD-OPTIONAL
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "supportedAlgorithms[] cannot be empty if provided"));
        } else {
            algorithms = request.getSupportedAlgorithms();
        }

        Set<String> capabilities = Set.copyOf(algorithms);
        Optional<PacketRecord> claimed =
                pullPacketService.claim(request.getWorkerId(), capabilities);

        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        PacketRecord packet = claimed.get();
        PullPacketResponse response =
                new PullPacketResponse(
                        packet.getPacketId(),
                        packet.getJobId(),
                        packet.getPacketPayloadJson(),
                        packet.getTimeoutAt());
        return ResponseEntity.ok(response);
    }

    /**
     * Handles {@link WorkerNotFoundException} → 404 Not Found.
     *
     * <p>Per spec section (b) error matrix: unknown resource → 404.
     */
    @ExceptionHandler(WorkerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleWorkerNotFound(WorkerNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    /** Handles {@link IllegalArgumentException} → 400 Bad Request. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailure(
            IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
