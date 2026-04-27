package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachedResult} DTO record.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * class.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-DTO; DEC-22
 */
class CachedResultTest {

    @Test
    void recordConstructorSetsAllFields() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x01);
        String gameMode = "standard";
        String payload = "{\"bestRank\":100,\"bestScore\":0.5}";
        Instant cachedAt = Instant.parse("2026-04-26T12:00:00Z");

        CachedResult result = new CachedResult(fingerprint, gameMode, payload, cachedAt);

        assertThat(result.structuralFingerprint()).isEqualTo(fingerprint);
        assertThat(result.gameMode()).isEqualTo(gameMode);
        assertThat(result.resultPayloadJson()).isEqualTo(payload);
        assertThat(result.cachedAt()).isEqualTo(cachedAt);
    }
}
