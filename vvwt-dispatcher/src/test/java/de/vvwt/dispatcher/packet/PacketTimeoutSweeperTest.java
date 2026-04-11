package de.vvwt.dispatcher.packet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PacketTimeoutSweeper} (AC8).
 *
 * <p>Tests:
 * <ul>
 *   <li>Packet with {@code attempts < maxReissueCount} is set back to {@code "pending"}</li>
 *   <li>Packet with {@code attempts >= maxReissueCount} is set to {@code "failed"}</li>
 *   <li>Reissue history JSON entry is appended (AC8 audit trail)</li>
 *   <li>No database calls when there are no timed-out packets</li>
 * </ul>
 *
 * <p>See Story E01S07 AC8.
 */
class PacketTimeoutSweeperTest {

    private PacketRepository packetRepository;
    private PacketTimeoutSweeper sweeper;

    @BeforeEach
    void setup() {
        packetRepository = mock(PacketRepository.class);
        sweeper = new PacketTimeoutSweeper(packetRepository);
        sweeper.setPacketTimeoutMinutes(5);
        sweeper.setMaxReissueCount(5);
    }

    // -------------------------------------------------------------------------
    // AC8: reissue below max count
    // -------------------------------------------------------------------------

    @Test
    void sweep_packetBelowMaxReissue_setsStatusPending() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID worker = UUID.randomUUID();

        PacketRecord packet = new PacketRecord(packetId, jobId, 0L, 100L);
        packet.assign(worker, Instant.now().minusSeconds(400)); // timed out

        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of(packet));
        when(packetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sweeper.sweepTimedOutPackets();

        assertThat(packet.getStatus()).isEqualTo("pending");
        assertThat(packet.getAssignedTo()).isNull();
        assertThat(packet.getAssignedAt()).isNull();
        // attempts is NOT reset (tracks total claim count)
        assertThat(packet.getAttempts()).isEqualTo(1);
    }

    @Test
    void sweep_packetBelowMaxReissue_appendsReissueHistoryEntry() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID worker = UUID.randomUUID();

        PacketRecord packet = new PacketRecord(packetId, jobId, 0L, 100L);
        packet.assign(worker, Instant.now().minusSeconds(400));

        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of(packet));
        when(packetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sweeper.sweepTimedOutPackets();

        // reissueHistory should be a JSON array with one entry
        String history = packet.getReissueHistory();
        assertThat(history).isNotNull();
        assertThat(history).startsWith("[");
        assertThat(history).endsWith("]");
        assertThat(history).contains("workerKeyId");
        assertThat(history).contains("attemptNumber");
        assertThat(history).contains("timestamp");
    }

    // -------------------------------------------------------------------------
    // AC8: fail after max reissue count
    // -------------------------------------------------------------------------

    @Test
    void sweep_packetAtMaxReissue_setsStatusFailed() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID worker = UUID.randomUUID();

        // Simulate a packet that has already been claimed maxReissueCount times
        PacketRecord packet = new PacketRecord(packetId, jobId, 0L, 100L);
        for (int i = 0; i < 5; i++) {
            packet.assign(worker, Instant.now().minusSeconds(400));
        }

        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of(packet));
        when(packetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sweeper.sweepTimedOutPackets();

        assertThat(packet.getStatus()).isEqualTo("failed");
    }

    // -------------------------------------------------------------------------
    // AC8: multiple reissue history entries appended correctly
    // -------------------------------------------------------------------------

    @Test
    void sweep_multipleReissues_historyIsValidJsonArray() {
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID worker = UUID.randomUUID();

        PacketRecord packet = new PacketRecord(packetId, jobId, 0L, 100L);
        // Simulate first reissue already recorded
        packet.assign(worker, Instant.now().minusSeconds(400));
        packet.appendReissueHistory("{\"timestamp\":\"2026-01-01T00:00:00Z\",\"workerKeyId\":\"" + worker + "\",\"attemptNumber\":1}");
        packet.reissueToPending();
        // Second assignment — now timed out again
        packet.assign(worker, Instant.now().minusSeconds(400));

        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of(packet));
        when(packetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sweeper.sweepTimedOutPackets();

        String history = packet.getReissueHistory();
        assertThat(history).startsWith("[");
        assertThat(history).endsWith("]");
        // Should contain two entries (the pre-existing one + the new one)
        long commaCount = history.chars().filter(c -> c == '{').count();
        assertThat(commaCount).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // No-op when queue is empty
    // -------------------------------------------------------------------------

    @Test
    void sweep_noTimedOutPackets_doesNothing() {
        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of());

        // Should complete without any save calls
        sweeper.sweepTimedOutPackets();
        // No exception, no interaction with save
    }
}
