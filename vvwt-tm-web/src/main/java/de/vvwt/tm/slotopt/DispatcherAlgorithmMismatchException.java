// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Thrown when the dispatcher returns an algorithm-mismatch error (HTTP 400 per E37S09 server
 * enforcement), indicating the registered algorithm does not match the algorithm expected by the
 * server for this worker's key registration.
 *
 * <p>This exception wraps the algorithm-mismatch condition without leaking server-internal detail
 * strings (AC-ALGORITHM-MISMATCH-PROPAGATED). Callers (specifically {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}) catch this exception and fall through
 * to Leg 3 (AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR).
 *
 * @see SlotOptimizationDispatcherClient
 * @see <a href="../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03 —
 *     AC-ALGORITHM-MISMATCH-PROPAGATED</a>
 */
public class DispatcherAlgorithmMismatchException extends RuntimeException {

    private final String algorithmId;
    private final int httpStatus;

    /**
     * Constructs the exception with algorithm context.
     *
     * @param algorithmId the algorithm ID that caused the mismatch
     * @param httpStatus the HTTP status code from the dispatcher response
     */
    public DispatcherAlgorithmMismatchException(String algorithmId, int httpStatus) {
        super(
                "Dispatcher algorithm mismatch: algorithm='"
                        + algorithmId
                        + "', httpStatus="
                        + httpStatus);
        this.algorithmId = algorithmId;
        this.httpStatus = httpStatus;
    }

    /** Returns the algorithm ID that caused the mismatch. */
    public String getAlgorithmId() {
        return algorithmId;
    }

    /** Returns the HTTP status code from the dispatcher response. */
    public int getHttpStatus() {
        return httpStatus;
    }
}
