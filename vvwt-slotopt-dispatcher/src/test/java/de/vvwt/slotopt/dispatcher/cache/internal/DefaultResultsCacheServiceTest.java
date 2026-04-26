package de.vvwt.slotopt.dispatcher.cache.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.cache.CachedResult;
import de.vvwt.slotopt.dispatcher.cache.CachedResultEntity;
import de.vvwt.slotopt.dispatcher.cache.CachedResultId;
import de.vvwt.slotopt.dispatcher.cache.CachedResultRepository;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultResultsCacheService}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * class.
 *
 * <p>DEC-35 / DEC-36: this test class is in {@code cache.internal} (same package as the
 * implementation). External references to the subject are typed as {@link ResultsCacheService} (the
 * public interface) to verify the contract, not the implementation class.
 *
 * <p>Story: E37S10; AC-RESULTS-CACHE-SERVICE; DEC-22, DEC-35, DEC-36
 */
@ExtendWith(MockitoExtension.class)
class DefaultResultsCacheServiceTest {

    @Mock private CachedResultRepository repository;

    @InjectMocks private DefaultResultsCacheService service;

    @Test
    void lookupReturnsMissWhenRepositoryFindsNothing() {
        byte[] fingerprint = fingerprint(0xAA);
        when(repository.findByKey(fingerprint, "default-mode")).thenReturn(Optional.empty());

        Optional<CachedResult> result = service.lookup(fingerprint, "default-mode");

        assertThat(result).isEmpty();
    }

    @Test
    void lookupReturnsCachedResultWhenRepositoryFindsEntity() {
        byte[] fingerprint = fingerprint(0xBB);
        String payload = "{\"bestRank\":7}";
        Instant cachedAt = Instant.parse("2026-04-26T10:00:00Z");

        CachedResultEntity entity = new CachedResultEntity();
        entity.setId(new CachedResultId(fingerprint, "test-mode"));
        entity.setResultPayloadJson(payload);
        entity.setCachedAt(cachedAt);
        entity.setFirstAcceptedJobId(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        when(repository.findByKey(fingerprint, "test-mode")).thenReturn(Optional.of(entity));

        Optional<CachedResult> result = service.lookup(fingerprint, "test-mode");

        assertThat(result).isPresent();
        assertThat(result.get().structuralFingerprint()).isEqualTo(fingerprint);
        assertThat(result.get().gameMode()).isEqualTo("test-mode");
        assertThat(result.get().resultPayloadJson()).isEqualTo(payload);
        assertThat(result.get().cachedAt()).isEqualTo(cachedAt);
    }

    @Test
    void recordAcceptedResultSavesEntityWithCorrectFields() {
        byte[] fingerprint = fingerprint(0xCC);
        String gameMode = "record-mode";
        String payload = "{\"bestRank\":3}";
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant before = Instant.now();

        service.recordAcceptedResult(fingerprint, gameMode, payload, jobId);

        ArgumentCaptor<CachedResultEntity> captor =
                ArgumentCaptor.forClass(CachedResultEntity.class);
        verify(repository).save(captor.capture());
        CachedResultEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(fingerprint);
        assertThat(saved.getGameMode()).isEqualTo(gameMode);
        assertThat(saved.getResultPayloadJson()).isEqualTo(payload);
        assertThat(saved.getFirstAcceptedJobId()).isEqualTo(jobId);
        assertThat(saved.getCachedAt()).isAfterOrEqualTo(before);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static byte[] fingerprint(int fill) {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) fill);
        return fp;
    }
}
