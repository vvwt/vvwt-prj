// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the submit-result endpoint.
 *
 * <p>Handles {@code POST /api/submit-result}. Accepts JSON {@link SubmitResultRequest}, delegates
 * to {@link SubmitResultService}, returns JSON {@link SubmitResultResponse}.
 *
 * <p>HTTP status codes per AC-SUBMIT-RESULT-CONTROLLER + spec section (b) Endpoint 4:
 *
 * <ul>
 *   <li>200 OK — result processed (accepted or superseded)
 *   <li>400 Bad Request — algorithm mismatch, malformed JSON
 *   <li>401 Unauthorized — unknown worker, invalid signature
 *   <li>404 Not Found — unknown packet
 *   <li>500 Internal Server Error — server misconfiguration (unknown algorithm)
 * </ul>
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-CONTROLLER; DEC-6, DEC-35, DEC-36
 */
@RestController
public class SubmitResultController {

    private final SubmitResultService submitResultService;

    public SubmitResultController(SubmitResultService submitResultService) {
        this.submitResultService = submitResultService;
    }

    /**
     * Accepts a signed result from a worker.
     *
     * @param request the submit-result request body
     * @param httpRequest the HTTP servlet request (for source IP extraction)
     * @return 200 OK with {@link SubmitResultResponse}
     */
    @PostMapping("/api/submit-result")
    public ResponseEntity<SubmitResultResponse> submitResult(
            @RequestBody SubmitResultRequest request, HttpServletRequest httpRequest) {
        String sourceIp = httpRequest.getRemoteAddr();
        SubmitResultResponse response = submitResultService.submit(request, sourceIp);
        return ResponseEntity.ok(response);
    }

    /** HTTP 400 — algorithm mismatch (AC-ALGORITHM-MISMATCH-REJECTED). */
    @ExceptionHandler(AlgorithmMismatchException.class)
    public ResponseEntity<Map<String, String>> handleAlgorithmMismatch(
            AlgorithmMismatchException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** HTTP 401 — unknown worker. */
    @ExceptionHandler(UnknownWorkerException.class)
    public ResponseEntity<Map<String, String>> handleUnknownWorker(UnknownWorkerException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", ex.getMessage()));
    }

    /** HTTP 401 — invalid signature. */
    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<Map<String, String>> handleInvalidSignature(
            InvalidSignatureException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Signature verification failed"));
    }

    /** HTTP 404 — unknown packet. */
    @ExceptionHandler(PacketNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePacketNotFound(PacketNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    /** HTTP 400 — malformed JSON. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed or invalid request body"));
    }

    /** HTTP 500 — server misconfiguration (algorithm not found in registry). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleServerMisconfiguration(
            IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Server configuration error"));
    }
}
