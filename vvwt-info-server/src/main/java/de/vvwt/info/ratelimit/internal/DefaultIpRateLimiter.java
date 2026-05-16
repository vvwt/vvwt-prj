package de.vvwt.info.ratelimit.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.vvwt.info.ratelimit.IpRateLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP token-bucket rate limiter backed by Caffeine (E38S07 AC2, AC8).
 *
 * <p>Maintains separate token buckets per (source-IP, {@link RateLimitType}) pair. Each bucket is
 * cached in Caffeine with a TTL of 2 minutes; stale entries are evicted automatically.
 *
 * <p>Library choice: Caffeine (Apache 2.0 — OSS-compliant per DEC-3). All transitive runtime
 * dependencies verified OSI-approved OSS (AC8).
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2,
 *     AC8</a>
 */
public class DefaultIpRateLimiter implements IpRateLimiter {

    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);
    private static final int CACHE_TTL_MINUTES = 2;

    private final int publisherRpm;
    private final int readerPollRpm;
    private final int readerWsRpm;

    // Cache key: "ip:type" e.g. "1.2.3.4:PUBLISHER"
    private final Cache<String, TokenBucket> bucketCache;

    public DefaultIpRateLimiter(int publisherRpm, int readerPollRpm, int readerWsRpm) {
        this.publisherRpm = publisherRpm;
        this.readerPollRpm = readerPollRpm;
        this.readerWsRpm = readerWsRpm;
        this.bucketCache =
                Caffeine.newBuilder()
                        .expireAfterAccess(CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                        .build();
    }

    /**
     * Attempts to consume one token for the given source IP and request type.
     *
     * @param sourceIp the resolved source IP address
     * @param type the rate-limit category
     * @return {@code true} if the request is within the limit; {@code false} if rate-limited
     */
    @Override
    public boolean tryConsume(String sourceIp, RateLimitType type) {
        String key = sourceIp + ":" + type.name();
        TokenBucket bucket =
                bucketCache.get(
                        key,
                        ignored ->
                                new TokenBucket(
                                        capacityFor(type),
                                        REFILL_PERIOD,
                                        java.time.Clock.systemUTC()));
        return bucket.tryConsume();
    }

    private int capacityFor(RateLimitType type) {
        return switch (type) {
            case PUBLISHER -> publisherRpm;
            case READER_POLL -> readerPollRpm;
            case READER_WS -> readerWsRpm;
        };
    }
}
