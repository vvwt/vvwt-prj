// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe token bucket for per-IP rate limiting (E38S07 AC2).
 *
 * <p>The bucket starts at {@code capacity} tokens. Each successful {@link #tryConsume()} removes
 * one token. When the elapsed time since the bucket was last refilled exceeds {@code refillPeriod},
 * the bucket is fully restored to {@code capacity}.
 *
 * <p>Implementation note: uses a simple "sliding window" reset rather than continuous drip refill.
 * A single period's worth of traffic is limited to {@code capacity} requests, then the window
 * resets. This is simpler to reason about and sufficient for Phase 1 baseline rate-limiting.
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2</a>
 */
public class TokenBucket {

    private final int capacity;
    private final long refillPeriodMillis;
    private final Clock clock;

    // Packed state: [31-bit token count | 32-bit lower refill epoch millis] — use two atomics
    // for simplicity and correctness under concurrent access.
    private final AtomicLong tokens;
    private final AtomicLong windowStartMillis;

    public TokenBucket(int capacity, Duration refillPeriod, Clock clock) {
        this.capacity = capacity;
        this.refillPeriodMillis = refillPeriod.toMillis();
        this.clock = clock;
        this.tokens = new AtomicLong(capacity);
        this.windowStartMillis = new AtomicLong(clock.instant().toEpochMilli());
    }

    /**
     * Attempts to consume one token.
     *
     * @return {@code true} if a token was available and consumed; {@code false} if the bucket is
     *     exhausted
     */
    public boolean tryConsume() {
        long now = clock.instant().toEpochMilli();
        long windowStart = windowStartMillis.get();

        if (now - windowStart >= refillPeriodMillis) {
            // Attempt to advance the window — only one thread should win
            if (windowStartMillis.compareAndSet(windowStart, now)) {
                tokens.set(capacity);
            }
        }

        // Decrement atomically if tokens > 0
        long current;
        do {
            current = tokens.get();
            if (current <= 0) {
                return false;
            }
        } while (!tokens.compareAndSet(current, current - 1));

        return true;
    }
}
