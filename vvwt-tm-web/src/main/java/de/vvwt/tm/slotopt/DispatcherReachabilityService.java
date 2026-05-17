// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Service interface for checking whether the vvwt-slotopt-dispatcher is reachable before attempting
 * Leg 2 HTTP dispatch (DEC-49 D-3, AC-REACHABILITY-SERVICE-AUTHORED).
 *
 * <p>Implementations perform a lightweight HTTP probe (e.g., HEAD request) to the configured
 * dispatcher base URL with a short connect+read timeout. Returns {@code true} iff the dispatcher
 * responds with HTTP 200 within the timeout; {@code false} on any non-200, network error, or
 * timeout.
 *
 * <h2>Null-URL behaviour</h2>
 *
 * <p>When {@code tm.slotopt.dispatcher.url} is null (default), {@link #isReachable()} returns
 * {@code false} immediately without making any HTTP call (AC-REACHABILITY-NULL-URL-RETURNS-FALSE,
 * per Brief D-12). This causes all N>threshold traffic to use Leg 3 in-process optimization.
 *
 * <h2>Tri-state status (E63S07 extension)</h2>
 *
 * <p>{@link #getStatus()} exposes the {@link DispatcherStatus} tri-state — distinguishing {@code
 * NOT_CONFIGURED} (null/blank URL, deliberate offline-first) from {@code UNREACHABLE} (configured
 * but probe failed). The probe mechanism is unchanged; only the return representation is extended.
 * {@link #isReachable()} is preserved for backward compatibility with routing code (DEC-49 D-3).
 *
 * <h2>Offline-operability</h2>
 *
 * <p>Per DEC-15 and Brief T-3, offline-operability is first-class. A dispatcher that is temporarily
 * unreachable must never block the optimization path — the routing client falls back to Leg 3
 * transparently.
 *
 * @see de.vvwt.tm.slotopt.internal.DefaultDispatcherReachabilityService
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3, D-12</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-15.md">DEC-15</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E63S07.story.md">Story E63S07 —
 *     AC-GOV-EXTENDS-REACHABILITY-SERVICE</a>
 */
public interface DispatcherReachabilityService {

    /**
     * Returns {@code true} if the dispatcher base URL is configured AND responds to a lightweight
     * HTTP probe within the configured timeout.
     *
     * <p>Preserved for routing code (DEC-49 D-3). Equivalent to {@code getStatus() ==
     * DispatcherStatus.REACHABLE}.
     *
     * @return {@code true} if the dispatcher is reachable; {@code false} if the URL is null, the
     *     server is unreachable, or the probe times out
     */
    boolean isReachable();

    /**
     * Returns the tri-state reachability status of the dispatcher (E63S07,
     * AC-GOV-EXTENDS-REACHABILITY-SERVICE).
     *
     * <p>The underlying probe mechanism (HEAD request + {@code reachabilityTimeoutMs} timeout) is
     * unchanged. Only the return representation is extended to distinguish:
     *
     * <ul>
     *   <li>{@link DispatcherStatus#NOT_CONFIGURED} — URL is null/blank; no probe attempted.
     *   <li>{@link DispatcherStatus#UNREACHABLE} — URL configured but probe failed.
     *   <li>{@link DispatcherStatus#REACHABLE} — URL configured and probe returned HTTP 2xx.
     * </ul>
     *
     * @return the current dispatcher reachability status; never {@code null}
     * @see DispatcherStatus
     */
    DispatcherStatus getStatus();
}
