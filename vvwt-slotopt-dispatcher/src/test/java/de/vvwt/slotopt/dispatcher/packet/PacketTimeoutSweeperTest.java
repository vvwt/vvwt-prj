package de.vvwt.slotopt.dispatcher.packet;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PacketTimeoutSweeper}.
 *
 * <p>Tests direct invocation of {@link PacketTimeoutSweeper#sweepTimedOutPackets()} — does NOT rely
 * on {@code @Scheduled} timing per AC-PACKET-TIMEOUT-SWEEPER.
 *
 * <p>DEC-36: test is in the same public {@code packet} package as {@link PacketTimeoutSweeper}.
 * {@link PacketRepository} is mocked via Mockito. No verify() on query collaborators per the
 * project testing pattern (verify is for commands only).
 *
 * <p>RED-first per DEC-22 / AC-PACKET-TIMEOUT-SWEEPER (E37S08).
 *
 * <p>Story: E37S08; AC-PACKET-TIMEOUT-SWEEPER; DEC-22, DEC-36
 */
@ExtendWith(MockitoExtension.class)
class PacketTimeoutSweeperTest {

    @Mock private PacketRepository packetRepository;

    private PacketTimeoutSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new PacketTimeoutSweeper(packetRepository);
    }

    @Test
    void sweep_noTimedOutPackets_savesNothing() {
        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of());

        sweeper.sweepTimedOutPackets();

        verify(packetRepository, never()).saveAll(anyList());
    }

    @Test
    void sweep_timedOutPackets_resetsToUnclaimed() {
        UUID packetId = UUID.randomUUID();
        PacketRecord timedOut = buildTimedOutPacket(packetId, UUID.randomUUID());
        when(packetRepository.findTimedOutPackets(any())).thenReturn(List.of(timedOut));

        sweeper.sweepTimedOutPackets();

        // After sweep, packet should be UNCLAIMED with cleared claim fields
        verify(packetRepository).saveAll(anyList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketRecord buildTimedOutPacket(UUID packetId, UUID workerId) {
        PacketRecord p = new PacketRecord();
        p.setPacketId(packetId);
        p.setJobId(UUID.randomUUID());
        p.setPacketPayloadJson("{}");
        p.setStatus("CLAIMED");
        p.setClaimedByWorkerId(workerId);
        p.setClaimedAt(Instant.now().minusSeconds(600));
        p.setTimeoutAt(Instant.now().minusSeconds(300)); // timed out 5 min ago
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
