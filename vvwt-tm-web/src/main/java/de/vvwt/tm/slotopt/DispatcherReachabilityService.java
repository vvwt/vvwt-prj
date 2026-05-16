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
 * <h2>Offline-operability</h2>
 *
 * <p>Per DEC-15 and Brief T-3, offline-operability is first-class. A dispatcher that is temporarily
 * unreachable must never block the optimization path — the routing client falls back to Leg 3
 * transparently.
 *
 * @see de.vvwt.tm.slotopt.internal.DefaultDispatcherReachabilityService
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-15.md">DEC-15</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S03.story.md">Story E27S03</a>
 */
public interface DispatcherReachabilityService {

    /**
     * Returns {@code true} if the dispatcher base URL is configured AND responds to a lightweight
     * HTTP probe within the configured timeout.
     *
     * @return {@code true} if the dispatcher is reachable; {@code false} if the URL is null, the
     *     server is unreachable, or the probe times out
     */
    boolean isReachable();
}
