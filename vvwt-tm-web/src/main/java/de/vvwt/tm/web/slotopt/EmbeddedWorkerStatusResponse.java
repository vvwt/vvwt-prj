// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import de.vvwt.tm.slotopt.EmbeddedWorkerControlService.WorkerStatus;
import de.vvwt.tm.slotopt.EmbeddedWorkerState;

/**
 * HTTP response body for embedded-worker status and control endpoints (E63S05).
 *
 * <p>DEC-40 Clause B Pattern A: serialized directly (no wrapper). Placed in {@code
 * de.vvwt.tm.web.slotopt} consistently with the existing slot-optimization admin surface.
 *
 * @param state the worker state name (e.g. {@code "RUNNING"}, {@code "STOPPED"})
 * @param stateCode numeric code for dashboard/alert use: 0=STOPPED, 1=RUNNING,
 *     2=PAUSED_BY_HOST_ACTIVITY, 3=PAUSED_BY_OPERATOR, 4=ERROR
 * @param packetsCompleted total packets successfully processed since last start
 * @param packetsFailed total packets that failed since last start
 * @see EmbeddedWorkerControlController
 */
public record EmbeddedWorkerStatusResponse(
        String state, int stateCode, long packetsCompleted, long packetsFailed) {

    /**
     * Creates a response from a live {@link WorkerStatus}.
     *
     * @param status the current worker status
     * @return the response DTO
     */
    public static EmbeddedWorkerStatusResponse from(WorkerStatus status) {
        return new EmbeddedWorkerStatusResponse(
                status.state().name(),
                status.state().numericCode(),
                status.packetsCompleted(),
                status.packetsFailed());
    }

    /**
     * Creates a response for a flag-disabled worker (no {@link
     * de.vvwt.tm.slotopt.EmbeddedWorkerControlService} bean present).
     *
     * <p>AC-ERR-METRICS-WHEN-DISABLED: always returns a defined stopped response, not an error.
     *
     * @return a response representing STOPPED state with zero counters
     */
    public static EmbeddedWorkerStatusResponse stopped() {
        return new EmbeddedWorkerStatusResponse(
                EmbeddedWorkerState.STOPPED.name(),
                EmbeddedWorkerState.STOPPED.numericCode(),
                0L,
                0L);
    }
}
