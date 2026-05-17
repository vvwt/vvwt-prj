// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import de.vvwt.tm.slotopt.EmbeddedWorkerControlService;
import de.vvwt.tm.slotopt.EmbeddedWorkerControlService.ControlResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for embedded-worker operator controls + observability (E63S05).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>{@code GET /api/slotopt/embedded-worker/status} — returns current worker state and counters
 *       ({@link EmbeddedWorkerStatusResponse}); always returns 200 (even when disabled).
 *   <li>{@code POST /api/slotopt/embedded-worker/pause} — pauses the worker; 200 on success, 409
 *       when not controllable (AC-ERR-CONTROL-ON-DISABLED-WORKER).
 *   <li>{@code POST /api/slotopt/embedded-worker/resume} — resumes a paused worker; 200 on success,
 *       409 when not controllable.
 *   <li>{@code POST /api/slotopt/embedded-worker/disable} — permanently disables the worker for
 *       this JVM session; 200 on success, 409 when not controllable.
 * </ul>
 *
 * <h2>Placement (DEC-40 Clause B Pattern A)</h2>
 *
 * <p>Placed in {@code de.vvwt.tm.web.slotopt} consistently with {@link
 * SlotOptimizationCancelController}. DTOs serialized directly (Pattern A).
 *
 * <h2>Auth (AC-SEC-CONTROL-ENDPOINTS-ADMIN-AUTHENTICATED)</h2>
 *
 * <p>All endpoints are under {@code /api/slotopt/embedded-worker} and inherit the admin
 * authentication configured in {@code AuthConfiguration} (HTTP Basic, admin credentials via {@code
 * AdminCredentialsProvider}).
 *
 * <h2>DEC-58 Clause D / DEC-72 Clause D-ext</h2>
 *
 * <p>{@code @RestController} beans are excluded from the interface mandate — this class does NOT
 * need a corresponding public interface.
 *
 * @see EmbeddedWorkerControlService
 * @see EmbeddedWorkerStatusResponse
 */
@RestController
@RequestMapping("/api/slotopt/embedded-worker")
public class EmbeddedWorkerControlController {

    private static final Logger LOG =
            LoggerFactory.getLogger(EmbeddedWorkerControlController.class);

    private final Optional<EmbeddedWorkerControlService> controlServiceOpt;

    /**
     * Constructs the controller.
     *
     * @param controlServiceOpt the control service (absent when the worker flag is disabled)
     */
    public EmbeddedWorkerControlController(
            Optional<EmbeddedWorkerControlService> controlServiceOpt) {
        this.controlServiceOpt = controlServiceOpt;
    }

    /**
     * {@code GET /api/slotopt/embedded-worker/status}
     *
     * <p>Returns the current worker state and counters. Always 200. AC-ERR-METRICS-WHEN-DISABLED: a
     * disabled worker reports STOPPED with zero counters.
     *
     * @return HTTP 200 with {@link EmbeddedWorkerStatusResponse}
     */
    @GetMapping("/status")
    public ResponseEntity<EmbeddedWorkerStatusResponse> getStatus() {
        if (controlServiceOpt.isEmpty()) {
            return ResponseEntity.ok(EmbeddedWorkerStatusResponse.stopped());
        }
        return ResponseEntity.ok(
                EmbeddedWorkerStatusResponse.from(controlServiceOpt.get().getStatus()));
    }

    /**
     * {@code POST /api/slotopt/embedded-worker/pause}
     *
     * <p>Pauses the embedded worker. Non-blocking.
     *
     * @return HTTP 200 with outcome on success; HTTP 409 if not controllable
     */
    @PostMapping("/pause")
    public ResponseEntity<ControlResultResponse> pause() {
        if (controlServiceOpt.isEmpty()) {
            return workerNotEnabled("pause");
        }
        ControlResult result = controlServiceOpt.get().pause();
        return toResponse(result);
    }

    /**
     * {@code POST /api/slotopt/embedded-worker/resume}
     *
     * <p>Resumes the embedded worker after an operator pause. Non-blocking.
     *
     * @return HTTP 200 with outcome on success; HTTP 409 if not controllable
     */
    @PostMapping("/resume")
    public ResponseEntity<ControlResultResponse> resume() {
        if (controlServiceOpt.isEmpty()) {
            return workerNotEnabled("resume");
        }
        ControlResult result = controlServiceOpt.get().resume();
        return toResponse(result);
    }

    /**
     * {@code POST /api/slotopt/embedded-worker/disable}
     *
     * <p>Permanently disables the embedded worker for this JVM session. Non-blocking.
     *
     * @return HTTP 200 with outcome on success; HTTP 409 if not controllable
     */
    @PostMapping("/disable")
    public ResponseEntity<ControlResultResponse> disable() {
        if (controlServiceOpt.isEmpty()) {
            return workerNotEnabled("disable");
        }
        ControlResult result = controlServiceOpt.get().disable();
        return toResponse(result);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<ControlResultResponse> toResponse(ControlResult result) {
        LOG.info(
                "EmbeddedWorkerControlController: operation result success={} conflict={}"
                        + " state={} message={}",
                result.success(),
                result.conflict(),
                result.state(),
                result.message());
        if (result.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            new ControlResultResponse(
                                    result.success(),
                                    result.message(),
                                    result.state() != null ? result.state().name() : null,
                                    result.state() != null ? result.state().numericCode() : 0));
        }
        return ResponseEntity.ok(
                new ControlResultResponse(
                        result.success(),
                        result.message(),
                        result.state() != null ? result.state().name() : null,
                        result.state() != null ? result.state().numericCode() : 0));
    }

    private ResponseEntity<ControlResultResponse> workerNotEnabled(String operation) {
        LOG.warn(
                "EmbeddedWorkerControlController: {} invoked but embedded worker is not enabled"
                        + " (flag-disabled)",
                operation);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ControlResultResponse(
                                false,
                                "Embedded worker is not enabled"
                                        + " (tm.slotopt.embedded-worker.enabled is not true)."
                                        + " Enable it at boot time and restart the host.",
                                "STOPPED",
                                0));
    }

    // -------------------------------------------------------------------------
    // Response DTO
    // -------------------------------------------------------------------------

    /**
     * Response body for control operations (pause / resume / disable).
     *
     * @param success whether the operation changed the worker state
     * @param message human-readable outcome description
     * @param state the worker state name after the operation
     * @param stateCode the numeric state code after the operation
     */
    public record ControlResultResponse(
            boolean success, String message, String state, int stateCode) {}
}
