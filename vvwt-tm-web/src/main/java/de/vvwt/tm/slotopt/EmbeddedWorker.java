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
 * <p>Story: E63S03 AC-GOV-INTERFACE-MANDATE.
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
}
