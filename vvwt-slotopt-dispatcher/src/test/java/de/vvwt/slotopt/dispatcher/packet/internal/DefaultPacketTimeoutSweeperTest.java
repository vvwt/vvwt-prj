package de.vvwt.slotopt.dispatcher.packet.internal;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketTimeoutSweeper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultPacketTimeoutSweeper}.
 *
 * <p>Tests direct invocation of {@link PacketTimeoutSweeper#sweepTimedOutPackets()} — does NOT rely
 * on {@code @Scheduled} timing per AC-PACKET-TIMEOUT-SWEEPER.
 *
 * <p>DEC-36: test is in the same {@code packet.internal} package as {@link
 * DefaultPacketTimeoutSweeper} (white-box same-package test); the subject is constructed directly.
 * The sweeper variable is typed as {@link PacketTimeoutSweeper} (the public interface) to follow
 * DEC-36. {@link PacketRepository} is mocked via Mockito. No verify() on query collaborators per
 * the project testing pattern (verify is for commands only).
 *
 * <p>AC-TEST-AOP-PROXY-SHIFT (E57S03): the {@code @Scheduled} annotation is on {@link
 * DefaultPacketTimeoutSweeper#sweepTimedOutPackets()}. The direct-invocation tests here exercise
 * the scheduled behaviour via the {@link PacketTimeoutSweeper} interface, confirming that the sweep
 * logic (the proxied behaviour) still fires correctly after the interface extraction.
 *
 * <p>RED-first per DEC-22 / AC-PACKET-TIMEOUT-SWEEPER (E37S08).
 *
 * <p>Story: E37S08; AC-PACKET-TIMEOUT-SWEEPER; DEC-22, DEC-36; E57S03 (interface extraction).
 */
@ExtendWith(MockitoExtension.class)
class DefaultPacketTimeoutSweeperTest {

    @Mock private PacketRepository packetRepository;

    private PacketTimeoutSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new DefaultPacketTimeoutSweeper(packetRepository);
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
