// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Lifecycle interface for the TM embedded slot-optimization worker (E63S03).
 *
 * <p>The embedded worker runs as a background thread inside the TM host process, contributing
 * compute cycles to the shared dispatcher pool. It is {@code OFF} by default and activated only
 * when {@code tm.slotopt.embedded-worker.enabled=true} (AC-GOV-OPT-IN-CONFIG).
 *
 * <p>DEC-58/DEC-72: public interface in the {@code slotopt} bounded-context root package; canonical
 * implementation {@link de.vvwt.tm.slotopt.internal.DefaultEmbeddedWorker} in {@code
 * slotopt.internal}.
 *
 * <p>Story: E63S03 AC-GOV-INTERFACE-MANDATE. Extended by E63S05 (operator controls +
 * observability).
 */
public interface EmbeddedWorker {

    /**
     * Starts the background worker thread.
     *
     * <p>Generates or loads the embedded worker's keypair, registers with the dispatcher, and
     * begins the pull-solve-submit loop. If already running, this call is a no-op.
     */
    void start();

    /**
     * Stops the background worker thread.
     *
     * <p>Signals shutdown and waits for the current iteration (if any) to complete. After this
     * call, the worker thread is no longer alive. If already stopped, this call is a no-op.
     */
    void stop();

    /**
     * Returns {@code true} if the worker background thread is currently running.
     *
     * @return {@code true} if running, {@code false} otherwise
     */
    boolean isRunning();

    // -------------------------------------------------------------------------
    // E63S05: operator controls + observability
    // -------------------------------------------------------------------------

    /**
     * Returns the current lifecycle state of the worker (E63S05).
     *
     * @return the current {@link EmbeddedWorkerState}; never {@code null}
     */
    EmbeddedWorkerState getState();

    /**
     * Pauses the worker (operator control) (E63S05).
     *
     * <p>Transitions the worker to {@link EmbeddedWorkerState#PAUSED_BY_OPERATOR} if it is in a
     * controllable state. Non-blocking — signals the worker thread and returns immediately.
     */
    void pause();

    /**
     * Resumes the worker after an operator pause (E63S05).
     *
     * <p>Transitions the worker from {@link EmbeddedWorkerState#PAUSED_BY_OPERATOR} back to {@link
     * EmbeddedWorkerState#RUNNING}. Non-blocking.
     */
    void resume();

    /**
     * Permanently disables the worker for this JVM session (E63S05).
     *
     * <p>Stops the worker thread and prevents restart. A host restart is required to re-enable.
     * Non-blocking.
     */
    void disable();

    /**
     * Returns the total number of packets successfully processed since the last {@link #start()}
     * (E63S05).
     *
     * @return non-negative packet count
     */
    long getPacketsCompleted();

    /**
     * Returns the total number of packets that failed (ComputeStepException) since the last {@link
     * #start()} (E63S05).
     *
     * @return non-negative failure count
     */
    long getPacketsFailed();
}
