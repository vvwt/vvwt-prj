package de.vvwt.slotopt.dispatcher.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CacheCorruptionException}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S10). Written before production
 * class.
 *
 * <p>Story: E37S10; AC-CACHE-CORRUPTION-EXCEPTION; DEC-22
 */
class CacheCorruptionExceptionTest {

    @Test
    void extendsRuntimeException() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x0F);
        CacheCorruptionException ex = new CacheCorruptionException(fingerprint, "payload schema drift detected");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void constructorSetsMessage() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x01);
        String reason = "canonical re-verification failed";

        CacheCorruptionException ex = new CacheCorruptionException(fingerprint, reason);

        assertThat(ex.getMessage()).contains(reason);
    }

    @Test
    void constructorExposesFingerprint() {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0x02);

        CacheCorruptionException ex = new CacheCorruptionException(fingerprint, "drift");

        assertThat(ex.getFingerprint()).isEqualTo(fingerprint);
    }
}
