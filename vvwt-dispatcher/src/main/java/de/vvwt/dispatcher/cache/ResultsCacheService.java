package de.vvwt.dispatcher.cache;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache read/write service for the public results database (AC7, AC8, AC12 of E01S09).
 *
 * <h2>Lookup (AC7)</h2>
 *
 * Returns an {@link Optional} of the best known result for a given structural fingerprint and
 * version combination. Returns {@link Optional#empty()} on cache miss. Hit/miss is logged at DEBUG
 * (high volume, AC12).
 *
 * <h2>Write (AC8)</h2>
 *
 * Idempotent on identical {@code (bestRank, bestScore)}; throws {@link CacheCorruptionException} on
 * diverging values for the same composite key. Writes are logged at INFO with the fingerprint hex
 * prefix and {@code (n, bestScore)} per AC12.
 *
 * <h2>Startup (AC12)</h2>
 *
 * Emits the total row count at INFO on application startup for ops awareness.
 */
@Service
public class ResultsCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResultsCacheService.class);

    private final CachedResultRepository repository;

    public ResultsCacheService(CachedResultRepository repository) {
        this.repository = repository;
    }

    /** Logs the total number of cached results at startup (AC12). */
    @PostConstruct
    public void logRowCountAtStartup() {
        long rowCount = repository.count();
        log.info("ResultsCacheService initialized — total cached results: {}", rowCount);
    }

    /**
     * Looks up a cached result by its composite key (AC7).
     *
     * @param fingerprint 32-byte SHA-256 fingerprint
     * @param scoreFnVersion scorer algorithm version
     * @param canonicalizationVersion canonicalization algorithm version
     * @return the cached result, or {@link Optional#empty()} on cache miss
     */
    @Transactional(readOnly = true)
    public Optional<CachedResult> lookup(
            byte[] fingerprint, int scoreFnVersion, int canonicalizationVersion) {
        CachedResultId id =
                new CachedResultId(fingerprint, scoreFnVersion, canonicalizationVersion);
        Optional<CachedResultEntity> entity = repository.findById(id);

        if (entity.isPresent()) {
            log.debug(
                    "Cache HIT fingerprint={} scoreFnVersion={} canonicalizationVersion={}",
                    fingerprintPrefix(fingerprint),
                    scoreFnVersion,
                    canonicalizationVersion);
        } else {
            log.debug(
                    "Cache MISS fingerprint={} scoreFnVersion={} canonicalizationVersion={}",
                    fingerprintPrefix(fingerprint),
                    scoreFnVersion,
                    canonicalizationVersion);
        }
        return entity.map(this::toValue);
    }

    /**
     * Writes a result to the cache (AC8).
     *
     * <p>Idempotent: if an identical entry already exists, this method is a no-op. Throws {@link
     * CacheCorruptionException} if the same composite key already exists with different {@code
     * (bestRank, bestScore)} values.
     *
     * @param fingerprint 32-byte SHA-256 fingerprint
     * @param scoreFnVersion scorer algorithm version
     * @param canonicalizationVersion canonicalization algorithm version
     * @param bestRank best permutation rank found by workers
     * @param bestScore variety score for {@code bestRank}
     * @param n number of avatars in the phase
     * @param sourceJobId the job that produced this result
     * @throws CacheCorruptionException if an existing entry has different {@code (bestRank,
     *     bestScore)} values for the same composite key
     */
    @Transactional
    public void write(
            byte[] fingerprint,
            int scoreFnVersion,
            int canonicalizationVersion,
            long bestRank,
            double bestScore,
            int n,
            UUID sourceJobId) {

        CachedResultId id =
                new CachedResultId(fingerprint, scoreFnVersion, canonicalizationVersion);
        Optional<CachedResultEntity> existing = repository.findById(id);

        if (existing.isPresent()) {
            CachedResultEntity existingEntity = existing.get();
            if (existingEntity.getBestRank() == bestRank
                    && Double.compare(existingEntity.getBestScore(), bestScore) == 0) {
                // Idempotent — identical values, no-op
                return;
            }
            // Diverging values for the same key — corruption detected
            throw new CacheCorruptionException(
                    "Cache corruption detected for fingerprint="
                            + fingerprintPrefix(fingerprint)
                            + " scoreFnVersion="
                            + scoreFnVersion
                            + " canonicalizationVersion="
                            + canonicalizationVersion
                            + ": existing=(rank="
                            + existingEntity.getBestRank()
                            + ", score="
                            + existingEntity.getBestScore()
                            + ") new=(rank="
                            + bestRank
                            + ", score="
                            + bestScore
                            + ")");
        }

        CachedResultEntity entity =
                new CachedResultEntity(id, bestRank, bestScore, n, Instant.now(), sourceJobId);
        repository.save(entity);

        log.info(
                "Cache WRITE fingerprint={} n={} bestScore={}",
                fingerprintPrefix(fingerprint),
                n,
                bestScore);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CachedResult toValue(CachedResultEntity entity) {
        return new CachedResult(
                entity.getId().getFingerprint(),
                entity.getId().getScoreFnVersion(),
                entity.getId().getCanonicalizationVersion(),
                entity.getBestRank(),
                entity.getBestScore(),
                entity.getN(),
                entity.getComputedAt(),
                entity.getSourceJobId());
    }

    /**
     * Returns the first 16 hex characters (8 bytes) of the fingerprint for log messages (AC12:
     * "fingerprint hex prefix, first 16 chars").
     */
    private static String fingerprintPrefix(byte[] fingerprint) {
        if (fingerprint == null || fingerprint.length == 0) {
            return "<empty>";
        }
        // Encode the full fingerprint as hex and take the first 16 characters
        String fullHex = HexFormat.of().formatHex(fingerprint);
        return fullHex.length() >= 16 ? fullHex.substring(0, 16) : fullHex;
    }
}
