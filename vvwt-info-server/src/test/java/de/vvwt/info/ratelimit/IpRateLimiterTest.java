package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.ratelimit.internal.IpRateLimiter;
import de.vvwt.info.ratelimit.internal.RateLimitType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IpRateLimiter}.
 *
 * <p>DEC-22 RED-first. Verifies per-IP isolation and limit enforcement.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2</a>
 */
class IpRateLimiterTest {

    @Test
    void perIpLimit_nPlusOneRequest_rejected() {
        // limit = 2 rpm for easy testing
        IpRateLimiter limiter = new IpRateLimiter(2, 2, 2);

        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isFalse(); // N+1
    }

    @Test
    void differentIps_doNotShareBucket() {
        IpRateLimiter limiter = new IpRateLimiter(1, 1, 1);

        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isFalse(); // exhausted

        // Different IP — independent bucket
        assertThat(limiter.tryConsume("5.6.7.8", RateLimitType.PUBLISHER)).isTrue();
    }

    @Test
    void readerPollType_usesItsOwnBucket() {
        IpRateLimiter limiter = new IpRateLimiter(1, 1, 1);

        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.READER_POLL)).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.READER_POLL)).isFalse();
        // PUBLISHER type — separate bucket, still available
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.PUBLISHER)).isTrue();
    }

    @Test
    void readerWsType_usesItsOwnBucket() {
        IpRateLimiter limiter = new IpRateLimiter(1, 1, 1);

        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.READER_WS)).isTrue();
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.READER_WS)).isFalse();
        // READER_POLL type — separate bucket
        assertThat(limiter.tryConsume("1.2.3.4", RateLimitType.READER_POLL)).isTrue();
    }
}
