// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InterPacketThrottle}.
 *
 * <p>RED-first per DEC-22. Tests verify the inter-packet sleep computation: for a given solve time
 * and CPU ratio, the sleep duration proportionally bounds the averaged CPU share.
 *
 * <p>Story: E63S04 — AC-TEST-CPU-THROTTLE-RATIO support
 */
class InterPacketThrottleTest {

    // =========================================================================
    // computeSleepMs — ratio-based computation
    // =========================================================================

    @Test
    void ratioZeroPointFiveGivesEqualSolveAndSleepTime() {
        // ratio 0.5: solve 50%, sleep 50% → sleep = solve time
        InterPacketThrottle throttle = new InterPacketThrottle(0.5, 60_000L);
        long solveNs = 100_000_000L; // 100ms
        long sleepMs = throttle.computeSleepMs(solveNs);

        // Expected: 100 * (1/0.5 - 1) = 100 * 1 = 100ms
        assertThat(sleepMs).isBetween(95L, 105L);
    }

    @Test
    void ratioZeroPointTwentyFiveGivesTripleSolveAsSleep() {
        // ratio 0.25: worker gets 25% → sleep 75% → sleep = 3x solve time
        InterPacketThrottle throttle = new InterPacketThrottle(0.25, 60_000L);
        long solveNs = 100_000_000L; // 100ms
        long sleepMs = throttle.computeSleepMs(solveNs);

        // Expected: 100 * (1/0.25 - 1) = 100 * 3 = 300ms
        assertThat(sleepMs).isBetween(295L, 305L);
    }

    @Test
    void ratioOneProducesZeroSleep() {
        // ratio 1.0 → 100% CPU allowed → no sleep
        InterPacketThrottle throttle = new InterPacketThrottle(1.0, 60_000L);
        assertThat(throttle.computeSleepMs(100_000_000L)).isEqualTo(0L);
    }

    @Test
    void ratioAboveOneProducesZeroSleep() {
        // ratio > 1.0 → also no sleep (guard against misconfiguration)
        InterPacketThrottle throttle = new InterPacketThrottle(2.0, 60_000L);
        assertThat(throttle.computeSleepMs(100_000_000L)).isEqualTo(0L);
    }

    @Test
    void zeroSolveTimeProducesZeroSleep() {
        InterPacketThrottle throttle = new InterPacketThrottle(0.25, 60_000L);
        assertThat(throttle.computeSleepMs(0L)).isEqualTo(0L);
    }

    @Test
    void sleepIsCapppedByMaxSleepMs() {
        // Very low ratio with long solve time would produce huge sleep — cap applies
        InterPacketThrottle throttle = new InterPacketThrottle(0.1, 500L); // cap at 500ms
        long solveNs = 10_000_000_000L; // 10s → uncapped would be 90s sleep
        long sleepMs = throttle.computeSleepMs(solveNs);

        assertThat(sleepMs).isLessThanOrEqualTo(500L);
        assertThat(sleepMs).isEqualTo(500L); // should hit cap exactly
    }

    @Test
    void zeroMaxSleepMsDisablesThrottle() {
        // maxSleepMs=0 → no sleep regardless of ratio and solve time
        InterPacketThrottle throttle = new InterPacketThrottle(0.25, 0L);
        assertThat(throttle.computeSleepMs(100_000_000L)).isEqualTo(0L);
    }
}
