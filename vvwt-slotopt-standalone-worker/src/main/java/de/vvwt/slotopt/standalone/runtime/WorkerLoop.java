// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime;

/**
 * The runtime polling loop for the vvwt-slotopt-standalone-worker process.
 *
 * <p>After a successful bootstrap phase (algorithm validation + key registration), the process
 * enters this loop: continuously polling the dispatcher for packets, processing them via the
 * worker-lib {@code PacketSolver}, and submitting signed results.
 *
 * <p>The loop runs indefinitely until a shutdown signal (SIGINT/SIGTERM via JVM shutdown hook) is
 * received or an unrecoverable error causes termination.
 *
 * <p>DEC-35-by-analogy: public interface in the {@code runtime} package root; canonical
 * implementation in {@link de.vvwt.slotopt.standalone.runtime.internal.DefaultWorkerLoop}.
 *
 * <p>Story: E41S05 AC-WORKER-LOOP-INTERFACE.
 */
public interface WorkerLoop {

    /**
     * Runs the polling loop until a shutdown signal is received or an unrecoverable error occurs.
     *
     * <p>On graceful shutdown (SIGINT/SIGTERM via JVM shutdown hook): completes the in-flight
     * packet (if any), emits the {@code worker_stopped} event, and returns normally (exit code 0).
     *
     * <p>On unrecoverable error: throws {@link WorkerLoopException} carrying the appropriate exit
     * code per {@link de.vvwt.slotopt.standalone.bootstrap.ExitCode}:
     *
     * <ul>
     *   <li>{@code 78} — submit_rejected_deprecated (HTTP 410 at submit-result time)
     *   <li>{@code 75} — dispatcher_unreachable_runtime (I/O failure during polling)
     *   <li>{@code 130} — interrupted (thread interrupted unexpectedly)
     * </ul>
     *
     * @throws WorkerLoopException on unrecoverable error; carries exit code for process exit
     */
    void run() throws WorkerLoopException;
}
