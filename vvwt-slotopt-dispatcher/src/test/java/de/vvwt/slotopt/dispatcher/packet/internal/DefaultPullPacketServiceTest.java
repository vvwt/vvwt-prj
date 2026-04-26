package de.vvwt.slotopt.dispatcher.packet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.packet.WorkerNotFoundException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link DefaultPullPacketService}.
 *
 * <p>Same package (white-box per DEC-36 same-package rule). {@link KeyRegistrationRepository} and
 * {@link PacketRepository} are mocked via Mockito.
 *
 * <p>RED-first per DEC-22 / AC-PULL-PACKET-SERVICE (E37S08).
 *
 * <p>Story: E37S08; AC-PULL-PACKET-SERVICE; DEC-22, DEC-35, DEC-36
 */
@ExtendWith(MockitoExtension.class)
class DefaultPullPacketServiceTest {

    @Mock private PacketRepository packetRepository;

    @Mock private KeyRegistrationRepository keyRegistrationRepository;

    @Mock private JdbcTemplate jdbcTemplate;

    private DefaultPullPacketService service;

    @BeforeEach
    void setUp() {
        service =
                new DefaultPullPacketService(
                        packetRepository, keyRegistrationRepository, jdbcTemplate);
    }

    @Test
    void claim_workerNotRegistered_throwsWorkerNotFoundException() {
        UUID workerId = UUID.randomUUID();
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(workerId, Set.of("Ed25519")))
                .isInstanceOf(WorkerNotFoundException.class);
    }

    @Test
    void claim_noUnclaimedPackets_returnsEmpty() {
        UUID workerId = UUID.randomUUID();
        when(keyRegistrationRepository.findByWorkerId(workerId))
                .thenReturn(Optional.of(buildRegistration(workerId, "worker")));
        when(packetRepository.findFirstByStatusOrderById("UNCLAIMED")).thenReturn(Optional.empty());

        Optional<PacketRecord> result = service.claim(workerId, Set.of("Ed25519"));

        assertThat(result).isEmpty();
    }

    @Test
    void claim_unclaimedPacketExists_returnsClaimedPacket() {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        PacketRecord unclaimed = buildPacketRecord(packetId, UUID.randomUUID(), "UNCLAIMED");
        unclaimed.setId(1L);

        PacketRecord claimed = buildPacketRecord(packetId, unclaimed.getJobId(), "CLAIMED");
        claimed.setId(1L);
        claimed.setClaimedByWorkerId(workerId);

        when(keyRegistrationRepository.findByWorkerId(workerId))
                .thenReturn(Optional.of(buildRegistration(workerId, "worker")));
        when(packetRepository.findFirstByStatusOrderById("UNCLAIMED"))
                .thenReturn(Optional.of(unclaimed));
        // jdbcTemplate.update returns 1 (claim succeeded)
        when(jdbcTemplate.update(anyString(), any(), any(), any(), anyLong())).thenReturn(1);
        when(packetRepository.findById(1L)).thenReturn(Optional.of(claimed));

        Optional<PacketRecord> result = service.claim(workerId, Set.of("Ed25519"));

        assertThat(result).isPresent();
        assertThat(result.get().getClaimedByWorkerId()).isEqualTo(workerId);
    }

    @Test
    void claim_capabilitiesAreInformationalV1_doesNotFilter() {
        // Per Brief C-17: algorithm-blind. Any capability set yields the same unclaimed packet.
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        PacketRecord unclaimed = buildPacketRecord(packetId, UUID.randomUUID(), "UNCLAIMED");
        unclaimed.setId(2L);

        PacketRecord claimed = buildPacketRecord(packetId, unclaimed.getJobId(), "CLAIMED");
        claimed.setId(2L);
        claimed.setClaimedByWorkerId(workerId);

        when(keyRegistrationRepository.findByWorkerId(workerId))
                .thenReturn(Optional.of(buildRegistration(workerId, "worker")));
        when(packetRepository.findFirstByStatusOrderById("UNCLAIMED"))
                .thenReturn(Optional.of(unclaimed));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), anyLong())).thenReturn(1);
        when(packetRepository.findById(2L)).thenReturn(Optional.of(claimed));

        Optional<PacketRecord> result =
                service.claim(workerId, Set.of("Ed25519", "ML-DSA-44", "SLH-DSA-128f"));

        assertThat(result).isPresent();
        assertThat(result.get().getClaimedByWorkerId()).isEqualTo(workerId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PacketRecord buildPacketRecord(UUID packetId, UUID jobId, String status) {
        PacketRecord r = new PacketRecord();
        r.setPacketId(packetId);
        r.setJobId(jobId);
        r.setPacketPayloadJson("{}");
        r.setStatus(status);
        return r;
    }

    private static KeyRegistration buildRegistration(UUID workerId, String role) {
        KeyRegistration reg = new KeyRegistration();
        reg.setWorkerId(workerId);
        reg.setRole(role);
        return reg;
    }
}
