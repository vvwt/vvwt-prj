package de.vvwt.dispatcher.cache;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Immutable value object returned by
 * {@link ResultsCacheService#lookup(byte[], int, int)}.
 *
 * <p>Maps directly to the columns of {@link CachedResultEntity} but is decoupled
 * from the JPA entity for clean layer separation.
 *
 * @param fingerprint            32-byte SHA-256 fingerprint
 * @param scoreFnVersion         scorer algorithm version
 * @param canonicalizationVersion canonicalization algorithm version
 * @param bestRank               best permutation rank found by workers
 * @param bestScore              variety score for {@code bestRank}
 * @param n                      number of avatars in the phase
 * @param computedAt             timestamp when the result was finalized
 * @param sourceJobId            the job that produced this result first
 */
public record CachedResult(
        byte[] fingerprint,
        int scoreFnVersion,
        int canonicalizationVersion,
        long bestRank,
        double bestScore,
        int n,
        Instant computedAt,
        UUID sourceJobId) {

    /** Compact canonical constructor — defensive copy of byte array. */
    public CachedResult {
        if (fingerprint == null) {
            throw new IllegalArgumentException("fingerprint must not be null");
        }
        fingerprint = Arrays.copyOf(fingerprint, fingerprint.length);
    }

    /** Returns a defensive copy of the fingerprint bytes. */
    @Override
    public byte[] fingerprint() {
        return Arrays.copyOf(fingerprint, fingerprint.length);
    }
}
