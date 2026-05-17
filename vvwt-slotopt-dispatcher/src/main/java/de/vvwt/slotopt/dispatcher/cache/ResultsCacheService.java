// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.cache;

import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for the slot-optimiser result cache.
 *
 * <p>Per DEC-35: this interface lives in the public {@code cache} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.cache.internal.DefaultResultsCacheService}) lives in {@code
 * cache.internal}.
 *
 * <p>All consumers (controllers, services, tests) type their dependency as {@code
 * ResultsCacheService}, never as the implementation class (DEC-36).
 *
 * <p>Cache key semantics per DEC-9: the key is purely structural — {@code structuralFingerprint} is
 * the 32-byte SHA-256 of the canonical phase definition; {@code gameMode} discriminates
 * optimization objectives for the same topology. No UUIDs cross the optimizer boundary as cache
 * keys.
 *
 * <p>Story: E37S10; AC-RESULTS-CACHE-SERVICE; DEC-9, DEC-35, DEC-36
 */
public interface ResultsCacheService {

    /**
     * Looks up a cached result by its structural fingerprint and game mode.
     *
     * <p>Implementation contract:
     *
     * <ul>
     *   <li>Cache miss → returns {@link Optional#empty()}.
     *   <li>Cache hit → returns the stored {@link CachedResult}.
     *   <li>Deserialisation failure → throws {@link CacheCorruptionException} (caller decides how
     *       to handle; the default implementation logs WARN and treats as miss — see
     *       AC-CACHE-READ-SHORT-CIRCUIT).
     * </ul>
     *
     * @param structuralFingerprint the 32-byte SHA-256 structural fingerprint; must not be {@code
     *     null}
     * @param gameMode the game-mode discriminator; must not be {@code null}
     * @return the cached result, or empty if no entry exists for this key
     */
    Optional<CachedResult> lookup(byte[] structuralFingerprint, String gameMode);

    /**
     * Records an accepted result in the cache (write-once semantics per
     * AC-CACHE-WRITE-ON-ACCEPTED-RESULT).
     *
     * <p>Implementation contract:
     *
     * <ul>
     *   <li>On success: persists the result payload keyed by {@code (structuralFingerprint,
     *       gameMode)}.
     *   <li>On duplicate key: silently no-ops (write-once; first accepted result wins).
     *   <li>On storage failure: callers MUST absorb the exception (like audit failures). Failure
     *       must NOT block result acceptance.
     * </ul>
     *
     * @param structuralFingerprint the 32-byte SHA-256 structural fingerprint; must not be {@code
     *     null}
     * @param gameMode the game-mode discriminator; must not be {@code null}
     * @param resultPayloadJson the canonicalised JSON of the accepted result; must not be {@code
     *     null}
     * @param firstAcceptedJobId the UUID of the job that produced the accepted result
     *     (traceability)
     */
    void recordAcceptedResult(
            byte[] structuralFingerprint,
            String gameMode,
            String resultPayloadJson,
            UUID firstAcceptedJobId);
}
