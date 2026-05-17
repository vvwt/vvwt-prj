// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Lifecycle states for the embedded slot-optimization worker (E63S05).
 *
 * <p>The numeric encoding is fixed (AC-TEST-METRICS-EXPOSED): operator dashboards and alerts depend
 * on stable integer values.
 *
 * <ul>
 *   <li>{@link #STOPPED} = 0 — not running (config-disabled or self-stopped after error)
 *   <li>{@link #RUNNING} = 1 — actively pulling and solving packets
 *   <li>{@link #PAUSED_BY_HOST_ACTIVITY} = 2 — auto-paused because live-scoring is active (E63S04)
 *   <li>{@link #PAUSED_BY_OPERATOR} = 3 — operator-paused at run time (E63S05)
 *   <li>{@link #ERROR} = 4 — self-stopped after a persistent non-recoverable failure (E63S03)
 * </ul>
 *
 * <p>Story: E63S05 AC-TEST-METRICS-EXPOSED.
 */
public enum EmbeddedWorkerState {

    /** Not running — config-disabled at boot, or stopped after {@link #ERROR}. Numeric code 0. */
    STOPPED,

    /** Actively pulling and solving packets. Numeric code 1. */
    RUNNING,

    /**
     * Automatically paused because live-scoring is active (E63S04 host-protection). The worker
     * resumes automatically when live-scoring ends. Numeric code 2.
     */
    PAUSED_BY_HOST_ACTIVITY,

    /**
     * Paused by an operator control invocation (E63S05). The worker waits for an explicit {@code
     * resume()} call. Numeric code 3.
     */
    PAUSED_BY_OPERATOR,

    /**
     * Self-stopped after a persistent non-recoverable failure (e.g., registration rejected by
     * dispatcher with HTTP 410). A host restart is required to recover. Numeric code 4.
     */
    ERROR;

    /**
     * Returns the fixed numeric code for this state (AC-TEST-METRICS-EXPOSED).
     *
     * <p>Codes are stable across deployments — operator dashboards and Prometheus alert rules rely
     * on them.
     *
     * @return ordinal-based integer code: STOPPED=0, RUNNING=1, PAUSED_BY_HOST_ACTIVITY=2,
     *     PAUSED_BY_OPERATOR=3, ERROR=4
     */
    public int numericCode() {
        return this.ordinal();
    }
}
