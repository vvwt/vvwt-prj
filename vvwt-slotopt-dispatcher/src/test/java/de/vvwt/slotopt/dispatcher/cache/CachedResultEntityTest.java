package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachedResultEntity}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * class.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-ENTITY; DEC-22, DEC-35
 */
class CachedResultEntityTest {

    @Test
    void constructorAndGetterSetterRoundtrip() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xAB);
        String gameMode = "default";
        CachedResultId id = new CachedResultId(fingerprint, gameMode);
        String payload = "{\"bestRank\":42,\"bestScore\":0.4}";
        Instant cachedAt = Instant.parse("2026-04-26T10:00:00Z");
        UUID firstAcceptedJobId = UUID.randomUUID();

        CachedResultEntity entity = new CachedResultEntity();
        entity.setId(id);
        entity.setResultPayloadJson(payload);
        entity.setCachedAt(cachedAt);
        entity.setFirstAcceptedJobId(firstAcceptedJobId);

        // getId() returns byte[] (Persistable<byte[]>); verify fingerprint bytes
        assertThat(entity.getId()).isEqualTo(fingerprint);
        // getCompositeKey() assembles the full CachedResultId for service-layer use
        assertThat(entity.getCompositeKey()).isEqualTo(id);
        assertThat(entity.getResultPayloadJson()).isEqualTo(payload);
        assertThat(entity.getCachedAt()).isEqualTo(cachedAt);
        assertThat(entity.getFirstAcceptedJobId()).isEqualTo(firstAcceptedJobId);
    }
}
