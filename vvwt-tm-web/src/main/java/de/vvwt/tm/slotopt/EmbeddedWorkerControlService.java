// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Run-time operator control surface for the embedded slot-optimization worker (E63S05).
 *
 * <p>Provides pause, resume, and disable operations, each returning a {@link ControlResult}
 * describing the outcome. All operations are non-blocking — they signal the embedded worker thread
 * and return promptly without joining the thread or waiting for an in-flight packet to complete
 * (AC-ERR-CONTROL-INVOCATION-NON-BLOCKING).
 *
 * <p>DEC-58/DEC-72: public interface in the {@code slotopt} bounded-context root package; canonical
 * implementation {@link de.vvwt.tm.slotopt.internal.DefaultEmbeddedWorkerControlService} in {@code
 * slotopt.internal}.
 *
 * <p>Story: E63S05 AC-GOV-INTERFACE-MANDATE.
 */
public interface EmbeddedWorkerControlService {

    /**
     * Pauses the embedded worker.
     *
     * <p>Transitions the worker from {@link EmbeddedWorkerState#RUNNING} (or {@link
     * EmbeddedWorkerState#PAUSED_BY_HOST_ACTIVITY}) to {@link
     * EmbeddedWorkerState#PAUSED_BY_OPERATOR}. If the worker is already paused-by-operator, the
     * call is idempotent (AC-ERR-REDUNDANT-CONTROL-IDEMPOTENT). If the worker is not in a
     * controllable state (disabled or error), returns a 409-class result
     * (AC-ERR-CONTROL-ON-DISABLED-WORKER).
     *
     * @return the control outcome
     */
    ControlResult pause();

    /**
     * Resumes the embedded worker after an operator pause.
     *
     * <p>Transitions the worker from {@link EmbeddedWorkerState#PAUSED_BY_OPERATOR} to {@link
     * EmbeddedWorkerState#RUNNING}. If the worker is already running (not paused-by-operator), the
     * call is idempotent (AC-ERR-REDUNDANT-CONTROL-IDEMPOTENT). If the worker is not in a
     * controllable state (disabled or error), returns a 409-class result
     * (AC-ERR-CONTROL-ON-DISABLED-WORKER).
     *
     * @return the control outcome
     */
    ControlResult resume();

    /**
     * Permanently disables the embedded worker for this JVM session.
     *
     * <p>Stops the worker thread and marks the worker as runtime-disabled. The worker cannot be
     * re-enabled at run time — a host restart with the config flag enabled is required
     * (AC-GOV-CONTROLS-COMPLEMENT-CONFIG-FLAG). If the worker is already disabled or in error,
     * returns a 409-class result (AC-ERR-CONTROL-ON-DISABLED-WORKER).
     *
     * @return the control outcome
     */
    ControlResult disable();

    /**
     * Returns the current worker status (state + counters).
     *
     * <p>Always returns a defined result — never throws. A disabled/flag-disabled worker reports
     * {@link EmbeddedWorkerState#STOPPED} (AC-ERR-METRICS-WHEN-DISABLED).
     *
     * @return the current status
     */
    WorkerStatus getStatus();

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    /**
     * Outcome of a control invocation (pause / resume / disable).
     *
     * @param success {@code true} if the operation changed the worker state; {@code false} if it
     *     was a no-op (idempotent) or the worker is not in a controllable state
     * @param message human-readable outcome description
     * @param conflict {@code true} if the invocation was rejected because the worker is not in a
     *     controllable state (flag-disabled, runtime-disabled, or error) — maps to HTTP 409
     * @param state the worker state after the operation
     */
    record ControlResult(
            boolean success, String message, boolean conflict, EmbeddedWorkerState state) {}

    /**
     * Current status of the embedded worker.
     *
     * @param state the current worker state
     * @param packetsCompleted total packets successfully processed since last start
     * @param packetsFailed total packets that failed (ComputeStepException) since last start
     */
    record WorkerStatus(EmbeddedWorkerState state, long packetsCompleted, long packetsFailed) {}
}
