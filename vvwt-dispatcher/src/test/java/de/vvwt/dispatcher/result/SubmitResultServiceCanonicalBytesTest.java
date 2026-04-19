package de.vvwt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitResultService#buildCanonicalBytes72} (E01S08 AC2).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Total length is exactly 72 bytes
 *   <li>Each field occupies the correct byte range in the correct byte order
 *   <li>{@code bestScoreBits} is the IEEE 754 raw bits of the double (big-endian)
 * </ul>
 */
class SubmitResultServiceCanonicalBytesTest {

    @Test
    @DisplayName("AC2: canonical bytes total length is 72")
    void canonicalBytes_length_is72() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(
                        packetId, jobId, 42L, 3.14, 1_000_000L, workerId);

        assertThat(bytes).hasSize(72);
    }

    @Test
    @DisplayName("AC2: packetId occupies bytes 0–15 big-endian")
    void canonicalBytes_packetId_bigEndian() {
        UUID packetId = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(packetId, jobId, 0L, 0.0, 0L, workerId);

        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(packetId.getMostSignificantBits());
        expected.putLong(packetId.getLeastSignificantBits());

        byte[] slice = new byte[16];
        System.arraycopy(bytes, 0, slice, 0, 16);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: jobId occupies bytes 16–31 big-endian")
    void canonicalBytes_jobId_bigEndian() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = new UUID(0xDEADBEEFCAFEBABEL, 0x0102030405060708L);
        UUID workerId = UUID.randomUUID();

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(packetId, jobId, 0L, 0.0, 0L, workerId);

        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(jobId.getMostSignificantBits());
        expected.putLong(jobId.getLeastSignificantBits());

        byte[] slice = new byte[16];
        System.arraycopy(bytes, 16, slice, 0, 16);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: bestRank occupies bytes 32–39 big-endian")
    void canonicalBytes_bestRank_bigEndian() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        long bestRank = 0x0102030405060708L;

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(
                        packetId, jobId, bestRank, 0.0, 0L, workerId);

        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(bestRank);

        byte[] slice = new byte[8];
        System.arraycopy(bytes, 32, slice, 0, 8);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: bestScoreBits is Double.doubleToLongBits encoded big-endian in bytes 40–47")
    void canonicalBytes_bestScoreBits_doubleToLongBits() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        double bestScore = 3.141592653589793;

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(
                        packetId, jobId, 0L, bestScore, 0L, workerId);

        long bits = Double.doubleToLongBits(bestScore);
        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(bits);

        byte[] slice = new byte[8];
        System.arraycopy(bytes, 40, slice, 0, 8);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: permutationsScored occupies bytes 48–55 big-endian")
    void canonicalBytes_permutationsScored_bigEndian() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        long perms = 999_999_999_999L;

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(
                        packetId, jobId, 0L, 0.0, perms, workerId);

        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(perms);

        byte[] slice = new byte[8];
        System.arraycopy(bytes, 48, slice, 0, 8);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: workerKeyId occupies bytes 56–71 big-endian")
    void canonicalBytes_workerKeyId_bigEndian() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = new UUID(0xFEDCBA9876543210L, 0x0123456789ABCDEFL);

        byte[] bytes =
                SubmitResultService.buildCanonicalBytes72(packetId, jobId, 0L, 0.0, 0L, workerId);

        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(workerId.getMostSignificantBits());
        expected.putLong(workerId.getLeastSignificantBits());

        byte[] slice = new byte[16];
        System.arraycopy(bytes, 56, slice, 0, 16);
        assertThat(slice).isEqualTo(expected.array());
    }

    @Test
    @DisplayName("AC2: different bestScore produces different canonical bytes at position 40-47")
    void canonicalBytes_differentScore_differentBits() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();

        byte[] bytes1 =
                SubmitResultService.buildCanonicalBytes72(packetId, jobId, 0L, 1.0, 0L, workerId);
        byte[] bytes2 =
                SubmitResultService.buildCanonicalBytes72(packetId, jobId, 0L, 2.0, 0L, workerId);

        // Only bytes 40-47 should differ
        for (int i = 0; i < 72; i++) {
            if (i >= 40 && i < 48) {
                // score bits differ between 1.0 and 2.0
            } else {
                assertThat(bytes1[i]).as("byte at index %d", i).isEqualTo(bytes2[i]);
            }
        }
        // Confirm the bytes 40-47 ARE different
        boolean anyDiff = false;
        for (int i = 40; i < 48; i++) {
            if (bytes1[i] != bytes2[i]) {
                anyDiff = true;
                break;
            }
        }
        assertThat(anyDiff).as("score bits section must differ for different scores").isTrue();
    }
}
