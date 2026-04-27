package de.vvwt.slotopt.dispatcher.cache;

import java.time.Instant;

/**
 * DTO record for a cached result lookup response.
 *
 * <p>Per AC-CACHED-RESULT-DTO: immutable view of a cache entry returned by {@link
 * ResultsCacheService#lookup}. Does not expose {@code firstAcceptedJobId} (internal traceability
 * field — not part of the public cache API).
 *
 * <p>Per DEC-9: {@code structuralFingerprint} contains no UUIDs or identity-bearing attributes.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-DTO; DEC-9, DEC-35
 */
public record CachedResult(
        byte[] structuralFingerprint,
        String gameMode,
        String resultPayloadJson,
        Instant cachedAt) {}
