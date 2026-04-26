package de.vvwt.slotopt.dispatcher.cache.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.cache.CachedResultId;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachedResultIdWritingConverter} and {@link CachedResultIdReadingConverter}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * classes.
 *
 * <p>DEC-36: this test class is in {@code cache.internal} (same package as the converters), so
 * white-box access is permitted. {@link CachedResultId} is in a different package — referenced via
 * its public API only.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-REPOSITORY; DEC-22, DEC-36
 */
class CachedResultIdConverterTest {

    @Test
    void writingConverterProducesExpectedColumns() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xAB);
        CachedResultId id = new CachedResultId(fingerprint, "mode-X");

        CachedResultIdWritingConverter writer = new CachedResultIdWritingConverter();
        Map<String, Object> result = writer.convert(id);

        assertThat(result).containsKey("structural_fingerprint");
        assertThat(result).containsKey("game_mode");
        assertThat((byte[]) result.get("structural_fingerprint")).isEqualTo(fingerprint);
        assertThat(result.get("game_mode")).isEqualTo("mode-X");
    }

    @Test
    void readingConverterReconstitutesId() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x77);
        Map<String, Object> row = Map.of("structural_fingerprint", fingerprint, "game_mode", "mode-Y");

        CachedResultIdReadingConverter reader = new CachedResultIdReadingConverter();
        CachedResultId id = reader.convert(row);

        assertThat(id.structuralFingerprint()).isEqualTo(fingerprint);
        assertThat(id.gameMode()).isEqualTo("mode-Y");
    }

    @Test
    void writingThenReadingRoundtrip() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x55);
        CachedResultId original = new CachedResultId(fingerprint, "roundtrip-mode");

        CachedResultIdWritingConverter writer = new CachedResultIdWritingConverter();
        CachedResultIdReadingConverter reader = new CachedResultIdReadingConverter();

        Map<String, Object> written = writer.convert(original);
        CachedResultId restored = reader.convert(written);

        assertThat(restored).isEqualTo(original);
    }
}
