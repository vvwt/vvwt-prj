// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.ratelimit.internal.TokenBucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket}.
 *
 * <p>DEC-22 RED-first. Uses a controllable clock to avoid test timing dependencies.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2</a>
 */
class TokenBucketTest {

    // 600 rpm = 10 per second = 1 per 100ms
    private static final int CAPACITY = 3;
    private static final Duration REFILL_PERIOD = Duration.ofSeconds(1);

    @Test
    void firstNRequests_succeed_whenCapacityN() {
        Clock clock = fixedClock(Instant.EPOCH);
        TokenBucket bucket = new TokenBucket(CAPACITY, REFILL_PERIOD, clock);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void nPlusOneRequest_fails_whenCapacityN() {
        Clock clock = fixedClock(Instant.EPOCH);
        TokenBucket bucket = new TokenBucket(CAPACITY, REFILL_PERIOD, clock);

        bucket.tryConsume();
        bucket.tryConsume();
        bucket.tryConsume();

        assertThat(bucket.tryConsume()).isFalse(); // N+1th request
    }

    @Test
    void afterRefillPeriod_tokenRestored_nextRequestSucceeds() {
        AtomicLong epochMillis = new AtomicLong(0);
        Clock advanceable =
                new Clock() {
                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return Instant.ofEpochMilli(epochMillis.get());
                    }
                };
        TokenBucket bucket = new TokenBucket(1, REFILL_PERIOD, advanceable);

        assertThat(bucket.tryConsume()).isTrue(); // consumes token
        assertThat(bucket.tryConsume()).isFalse(); // exhausted

        // Advance clock past the refill period
        epochMillis.set(REFILL_PERIOD.toMillis() + 1);

        assertThat(bucket.tryConsume()).isTrue(); // token refilled
    }

    @Test
    void emptyBucket_doesNotGoBelowZero() {
        Clock clock = fixedClock(Instant.EPOCH);
        TokenBucket bucket = new TokenBucket(1, REFILL_PERIOD, clock);

        bucket.tryConsume(); // 0 tokens left
        boolean r1 = bucket.tryConsume(); // still 0
        boolean r2 = bucket.tryConsume(); // still 0

        assertThat(r1).isFalse();
        assertThat(r2).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
