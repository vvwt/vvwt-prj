package de.vvwt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@link ResultsCacheService} backed by H2 in-memory DB.
 *
 * <p>Covers AC7 (lookup), AC8 (write + idempotency + CacheCorruptionException).
 */
@DataJpaTest
@Import(ResultsCacheService.class)
@TestPropertySource(
        properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=always"})
class ResultsCacheServiceTest {

    private static final byte[] FINGERPRINT = new byte[32]; // 32 zero bytes — valid
    private static final int SCORE_FN_VERSION = 1;
    private static final int CANONICALIZATION_VERSION = 1;

    @Autowired private ResultsCacheService cacheService;

    @Test
    void lookup_miss_returnsEmpty() {
        Optional<CachedResult> result =
                cacheService.lookup(FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION);
        assertThat(result).isEmpty();
    }

    @Test
    void write_thenLookup_returnsResult() {
        UUID jobId = UUID.randomUUID();
        cacheService.write(
                FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION, 42L, 3.14, 8, jobId);

        Optional<CachedResult> result =
                cacheService.lookup(FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION);

        assertThat(result).isPresent();
        assertThat(result.get().bestRank()).isEqualTo(42L);
        assertThat(result.get().bestScore()).isEqualTo(3.14);
        assertThat(result.get().n()).isEqualTo(8);
        assertThat(result.get().sourceJobId()).isEqualTo(jobId);
    }

    @Test
    void write_sameTwice_isIdempotent() {
        UUID jobId = UUID.randomUUID();
        cacheService.write(
                FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION, 42L, 3.14, 8, jobId);
        // Same values again — must not throw
        cacheService.write(
                FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION, 42L, 3.14, 8, jobId);

        // Still only one entry
        Optional<CachedResult> result =
                cacheService.lookup(FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION);
        assertThat(result).isPresent();
    }

    @Test
    void write_conflictingValues_throwsCacheCorruptionException() {
        UUID jobId = UUID.randomUUID();
        cacheService.write(
                FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION, 42L, 3.14, 8, jobId);

        assertThatThrownBy(
                        () ->
                                cacheService.write(
                                        FINGERPRINT,
                                        SCORE_FN_VERSION,
                                        CANONICALIZATION_VERSION,
                                        99L,
                                        9.99,
                                        8,
                                        UUID.randomUUID()))
                .isInstanceOf(CacheCorruptionException.class)
                .hasMessageContaining("corruption");
    }

    @Test
    void lookup_differentVersion_returnsEmpty() {
        UUID jobId = UUID.randomUUID();
        cacheService.write(
                FINGERPRINT, SCORE_FN_VERSION, CANONICALIZATION_VERSION, 42L, 3.14, 8, jobId);

        // Different scoreFnVersion — different PK, different cache entry
        Optional<CachedResult> result =
                cacheService.lookup(FINGERPRINT, SCORE_FN_VERSION + 1, CANONICALIZATION_VERSION);
        assertThat(result).isEmpty();
    }
}
