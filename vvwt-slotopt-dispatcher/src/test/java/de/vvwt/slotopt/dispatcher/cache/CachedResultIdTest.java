package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachedResultId}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * class.
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-REPOSITORY; DEC-22, DEC-9
 */
class CachedResultIdTest {

    @Test
    void constructorSetsFields() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xAB);
        String gameMode = "default";

        CachedResultId id = new CachedResultId(fingerprint, gameMode);

        assertThat(id.structuralFingerprint()).isEqualTo(fingerprint);
        assertThat(id.gameMode()).isEqualTo(gameMode);
    }

    @Test
    void equalityBasedOnFingerprintBytesAndGameMode() {
        byte[] fp1 = new byte[32];
        byte[] fp2 = new byte[32];
        Arrays.fill(fp1, (byte) 0x01);
        Arrays.fill(fp2, (byte) 0x01);

        CachedResultId id1 = new CachedResultId(fp1, "mode-A");
        CachedResultId id2 = new CachedResultId(fp2, "mode-A");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    void differentFingerprintProducesInequality() {
        byte[] fp1 = new byte[32];
        byte[] fp2 = new byte[32];
        Arrays.fill(fp1, (byte) 0x01);
        Arrays.fill(fp2, (byte) 0x02);

        CachedResultId id1 = new CachedResultId(fp1, "mode-A");
        CachedResultId id2 = new CachedResultId(fp2, "mode-A");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void differentGameModeProducesInequality() {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) 0x01);

        CachedResultId id1 = new CachedResultId(fp, "mode-A");
        CachedResultId id2 = new CachedResultId(fp, "mode-B");

        assertThat(id1).isNotEqualTo(id2);
    }
}
