// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Tri-state reachability status of the vvwt-slotopt-dispatcher (E63S07,
 * AC-TEST-STATUS-DISTINGUISHES-NOT-CONFIGURED, DEC-49 D-12).
 *
 * <p>Extends the binary {@link DispatcherReachabilityService#isReachable()} result by
 * distinguishing between a deliberately-unconfigured dispatcher and a configured-but-unreachable
 * one:
 *
 * <ul>
 *   <li>{@link #NOT_CONFIGURED} — {@code tm.slotopt.dispatcher.url} is null or blank; no HTTP probe
 *       is attempted. This is a deliberate offline-first deployment (Leg 3 always per DEC-49 D-12).
 *       This is NOT an error state.
 *   <li>{@link #UNREACHABLE} — URL is configured but the HEAD probe failed (timeout, non-2xx,
 *       network error). Likely a misconfiguration or transient outage.
 *   <li>{@link #REACHABLE} — URL is configured and the HEAD probe succeeded with HTTP 2xx.
 * </ul>
 *
 * @see DispatcherReachabilityService#getStatus()
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-12</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E63S07.story.md">Story E63S07</a>
 */
public enum DispatcherStatus {

    /** The dispatcher URL is not configured ({@code tm.slotopt.dispatcher.url} is null/blank). */
    NOT_CONFIGURED,

    /** The dispatcher URL is configured but the reachability probe failed or timed out. */
    UNREACHABLE,

    /** The dispatcher URL is configured and the reachability probe succeeded (HTTP 2xx). */
    REACHABLE
}
