// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

/**
 * Inter-packet CPU throttle for the TM embedded worker (E63S04).
 *
 * <p>This is NEW design — it is NOT the standalone {@code CpuThrottle} (which provides idle-poll
 * pacing only, sleeping when no packet is available). The {@code InterPacketThrottle} bounds the
 * worker's <em>averaged CPU share</em> by sleeping proportionally after each solved packet,
 * ensuring the worker does not monopolize CPU while host processes (live scoring, admin UI) are
 * running.
 *
 * <h2>Algorithm</h2>
 *
 * <p>Given:
 *
 * <ul>
 *   <li>{@code cpuMaxRatio} — the worker's maximum allowed CPU share (e.g., 0.25 for 25%).
 *   <li>{@code solveTimeNs} — the nanoseconds the worker spent solving the just-completed packet.
 * </ul>
 *
 * <p>The required inter-packet sleep maintains the invariant:
 *
 * <pre>
 *   solveTime / (solveTime + sleepTime) ≤ cpuMaxRatio
 *   ⟹ sleepTime ≥ solveTime * (1 / cpuMaxRatio - 1)
 * </pre>
 *
 * <p>For example, at {@code cpuMaxRatio=0.25} and a 100ms solve, the sleep is 300ms, giving an
 * average CPU share of 100/(100+300) = 25%.
 *
 * <h2>DEC-58 / DEC-72 scope</h2>
 *
 * <p>{@code InterPacketThrottle} is a plain utility class with NO Spring stereotype annotation
 * ({@code @Service} / {@code @Component}). It is instantiated by {@link
 * EmbeddedWorkerConfiguration} and passed into {@link DefaultEmbeddedWorker} as a constructor
 * argument. The DEC-58/72 interface mandate applies only to Spring-managed service-shaped beans,
 * not to plain utility classes. No public interface is required.
 *
 * <p>Story: E63S04 — AC-GOV-INTER-PACKET-THROTTLE-IS-NEW, AC-TEST-CPU-THROTTLE-RATIO,
 * AC-TEST-THROTTLE-IS-BETWEEN-PACKETS.
 */
class InterPacketThrottle {

    private final double cpuMaxRatio;
    private final long maxSleepMs;

    /**
     * Constructs an {@code InterPacketThrottle}.
     *
     * @param cpuMaxRatio the worker's maximum allowed averaged CPU share in (0.0, 1.0]; values ≥
     *     1.0 disable throttling (no sleep)
     * @param maxSleepMs the maximum sleep duration (ms) applied after any packet; 0 disables
     *     throttling
     */
    InterPacketThrottle(double cpuMaxRatio, long maxSleepMs) {
        this.cpuMaxRatio = cpuMaxRatio;
        this.maxSleepMs = maxSleepMs;
    }

    /**
     * Computes the sleep duration to apply after solving a packet of the given duration.
     *
     * <p>Returns 0 when throttling is disabled (ratio ≥ 1.0 or maxSleepMs ≤ 0 or solve time is 0).
     * Returns at most {@code maxSleepMs} ms.
     *
     * @param solveTimeNs the elapsed solve time in nanoseconds; non-negative
     * @return the sleep duration in milliseconds; always ≥ 0
     */
    long computeSleepMs(long solveTimeNs) {
        if (cpuMaxRatio >= 1.0 || maxSleepMs <= 0L || solveTimeNs <= 0L) {
            return 0L;
        }
        // sleepMs = solveMs * (1 / ratio - 1)
        double solveMs = solveTimeNs / 1_000_000.0;
        double rawSleepMs = solveMs * (1.0 / cpuMaxRatio - 1.0);
        long sleepMs = Math.round(rawSleepMs);
        return Math.min(sleepMs, maxSleepMs);
    }
}
