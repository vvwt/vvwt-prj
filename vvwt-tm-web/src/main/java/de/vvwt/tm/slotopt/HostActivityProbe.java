// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Probe interface that reports whether the TM host is currently active with live-scoring.
 *
 * <p>This is the ONLY host-coupling channel allowed by the embedded worker (DEC-64 / C-5 in-process
 * read). The worker observes host live-scoring state exclusively through this interface. The worker
 * does NOT read the TM database, does NOT couple to the Saga-Orchestrator, and carries no business
 * logic in its usage — it reads a single boolean.
 *
 * <h2>Fail-safe contract</h2>
 *
 * <p>Callers MUST treat a probe exception as equivalent to {@code true} (host is active). This
 * conservative bias ensures a probe failure causes the worker to pause rather than charging ahead
 * and competing with TM's primary tournament-day duties (AC-ERR-PROBE-FAILURE-IS-CONSERVATIVE).
 *
 * <h2>DEC-58 / DEC-72</h2>
 *
 * <p>Public interface in the {@code slotopt} bounded-context root package. Canonical implementation
 * is {@link de.vvwt.tm.slotopt.internal.DefaultHostActivityProbe} in {@code slotopt.internal}.
 *
 * <p>Story: E63S04 — AC-GOV-HOST-ACTIVITY-PROBE-INTERFACE.
 */
public interface HostActivityProbe {

    /**
     * Returns {@code true} if the TM host is currently active with live-scoring.
     *
     * <p>Callers MUST catch all exceptions from this method and treat them as {@code true}
     * (conservative fail-safe). The method body SHOULD NOT throw but callers cannot rely on this
     * guarantee.
     *
     * @return {@code true} if live-scoring is currently active; {@code false} otherwise
     */
    boolean isLiveScoringActive();
}
