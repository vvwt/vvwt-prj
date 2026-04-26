package de.vvwt.slotopt.dispatcher.cache.internal;

import de.vvwt.slotopt.dispatcher.cache.CachedResult;
import de.vvwt.slotopt.dispatcher.cache.CachedResultEntity;
import de.vvwt.slotopt.dispatcher.cache.CachedResultId;
import de.vvwt.slotopt.dispatcher.cache.CachedResultRepository;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ResultsCacheService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code cache.internal}. All consumers reference
 * {@link ResultsCacheService} (the public interface) exclusively (DEC-36).
 *
 * <p>Write-once semantics: {@link #recordAcceptedResult} always performs {@code save()} which maps
 * to INSERT (because {@link CachedResultEntity#isNew()} always returns {@code true}). If a
 * duplicate-key exception is thrown by the DB (same fingerprint + gameMode), the exception
 * propagates; callers MUST absorb it per AC-CACHE-WRITE-ON-ACCEPTED-RESULT.
 *
 * <p>Story: E37S10; AC-RESULTS-CACHE-SERVICE; DEC-9, DEC-35, DEC-36
 */
@Service
class DefaultResultsCacheService implements ResultsCacheService {

    private final CachedResultRepository repository;

    DefaultResultsCacheService(CachedResultRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link CachedResultRepository#findByKey(byte[], String)} and maps the entity
     * to a {@link CachedResult} DTO.
     */
    @Override
    public Optional<CachedResult> lookup(byte[] structuralFingerprint, String gameMode) {
        return repository
                .findByKey(structuralFingerprint, gameMode)
                .map(
                        entity ->
                                new CachedResult(
                                        entity.getId(),
                                        entity.getGameMode(),
                                        entity.getResultPayloadJson(),
                                        entity.getCachedAt()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds a {@link CachedResultEntity} and persists it via {@link
     * CachedResultRepository#save(Object)}. The entity's {@code isNew()} is always {@code true},
     * forcing INSERT semantics.
     */
    @Override
    public void recordAcceptedResult(
            byte[] structuralFingerprint,
            String gameMode,
            String resultPayloadJson,
            UUID firstAcceptedJobId) {
        CachedResultEntity entity = new CachedResultEntity();
        entity.setId(new CachedResultId(structuralFingerprint, gameMode));
        entity.setResultPayloadJson(resultPayloadJson);
        entity.setCachedAt(Instant.now());
        entity.setFirstAcceptedJobId(firstAcceptedJobId);
        repository.save(entity);
    }
}
