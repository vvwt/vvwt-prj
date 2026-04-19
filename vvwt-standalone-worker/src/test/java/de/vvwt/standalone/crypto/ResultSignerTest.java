package de.vvwt.standalone.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.worker.types.PacketResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResultSigner} — canonical byte layout correctness (AC4 of E01S05). */
class ResultSignerTest {

    private static final UUID PACKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORKER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void buildCanonicalBytes_length_is72() {
        PacketResult result = new PacketResult(42L, 1.5, 100L, 999L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);
        assertThat(canonical).hasSize(ResultSigner.CANONICAL_BYTES_LENGTH);
    }

    @Test
    void buildCanonicalBytes_packetId_at_offset0() {
        PacketResult result = new PacketResult(0L, 0.0, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        long msb = buf.getLong(0);
        long lsb = buf.getLong(8);
        UUID parsedPacketId = new UUID(msb, lsb);
        assertThat(parsedPacketId).isEqualTo(PACKET_ID);
    }

    @Test
    void buildCanonicalBytes_jobId_at_offset16() {
        PacketResult result = new PacketResult(0L, 0.0, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        long msb = buf.getLong(16);
        long lsb = buf.getLong(24);
        UUID parsedJobId = new UUID(msb, lsb);
        assertThat(parsedJobId).isEqualTo(JOB_ID);
    }

    @Test
    void buildCanonicalBytes_bestRank_at_offset32_bigEndian() {
        long expectedRank = 0x0102030405060708L;
        PacketResult result = new PacketResult(expectedRank, 0.0, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        assertThat(buf.getLong(32)).isEqualTo(expectedRank);
    }

    @Test
    void buildCanonicalBytes_bestScoreBits_at_offset40_usingDoubleToLongBits() {
        double score = 3.14159;
        PacketResult result = new PacketResult(0L, score, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        assertThat(buf.getLong(40)).isEqualTo(Double.doubleToLongBits(score));
    }

    @Test
    void buildCanonicalBytes_permutationsScored_at_offset48() {
        long permutations = 10000L;
        PacketResult result = new PacketResult(0L, 0.0, permutations, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        assertThat(buf.getLong(48)).isEqualTo(permutations);
    }

    @Test
    void buildCanonicalBytes_workerKeyId_at_offset56() {
        PacketResult result = new PacketResult(0L, 0.0, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        long msb = buf.getLong(56);
        long lsb = buf.getLong(64);
        UUID parsedWorkerId = new UUID(msb, lsb);
        assertThat(parsedWorkerId).isEqualTo(WORKER_ID);
    }

    @Test
    void buildCanonicalBytes_nanScore_normalizedByDoubleToLongBits() {
        // Double.NaN has multiple representations — doubleToLongBits normalizes to canonical NaN
        double rawNan = Double.longBitsToDouble(0x7ff8000000000001L); // a non-canonical NaN
        PacketResult result = new PacketResult(0L, rawNan, 1L, 1L);
        byte[] canonical = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);

        ByteBuffer buf = ByteBuffer.wrap(canonical).order(ByteOrder.BIG_ENDIAN);
        // doubleToLongBits normalizes all NaN to 0x7ff8000000000000L
        assertThat(buf.getLong(40)).isEqualTo(Double.doubleToLongBits(rawNan));
    }

    @Test
    void buildCanonicalBytes_deterministic_sameInputSameOutput() {
        PacketResult result = new PacketResult(42L, 1.5, 100L, 999L);
        byte[] first = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);
        byte[] second = ResultSigner.buildCanonicalBytes(PACKET_ID, JOB_ID, result, WORKER_ID);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void buildCanonicalBytes_nullPacketId_throws() {
        PacketResult result = new PacketResult(0L, 0.0, 1L, 1L);
        assertThatThrownBy(() -> ResultSigner.buildCanonicalBytes(null, JOB_ID, result, WORKER_ID))
                .isInstanceOf(NullPointerException.class);
    }
}
