package de.vvwt.dispatcher.cache;

/**
 * Thrown by {@link ResultsCacheService#write} when a write attempt finds an
 * existing cache entry for the same composite key but with DIFFERENT
 * {@code (bestRank, bestScore)} values.
 *
 * <p>This situation should be impossible if the scorer is deterministic and
 * the canonicalization rule is correctly implemented — the same structural input
 * must always produce the same optimal result. Detection of this condition signals
 * a bug in the scorer, a canonicalization regression, or data corruption.
 *
 * <p>Per AC8 of E01S09: a write is idempotent on identical {@code (bestRank, bestScore)};
 * it throws this exception on diverging values for the same composite key.
 */
public class CacheCorruptionException extends RuntimeException {

    /**
     * Creates the exception with a message describing the conflicting values.
     *
     * @param message details about the conflict (fingerprint prefix, old vs new values)
     */
    public CacheCorruptionException(String message) {
        super(message);
    }
}
