package de.vvwt.slotopt.dispatcher.packet;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweeper that periodically reissues timed-out claimed packets.
 *
 * <p>Per AC-PACKET-TIMEOUT-SWEEPER: {@code @Scheduled} bean that scans for packets with {@code
 * status='CLAIMED' AND timeoutAt < now()} and resets them to {@code UNCLAIMED}, clearing claim
 * fields so they can be claimed by another worker.
 *
 * <p>The sweeper is designed to be testable via direct invocation of {@link
 * #sweepTimedOutPackets()} without relying on {@code @Scheduled} timing (per
 * AC-PACKET-TIMEOUT-SWEEPER).
 *
 * <p>Story: E37S08; AC-PACKET-TIMEOUT-SWEEPER; DEC-35
 */
@Component
public class PacketTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(PacketTimeoutSweeper.class);

    private final PacketRepository packetRepository;

    public PacketTimeoutSweeper(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    /**
     * Scans for timed-out {@code CLAIMED} packets and resets them to {@code UNCLAIMED}.
     *
     * <p>For each timed-out packet: sets {@code status = UNCLAIMED}, clears {@code
     * claimedByWorkerId}, {@code claimedAt}, and {@code timeoutAt}.
     *
     * <p>Scheduled every 30 seconds (Delivery-tunable via {@code
     * dispatcher.packet.sweeper.interval-ms}). Direct invocation is supported for testing.
     */
    @Scheduled(fixedDelayString = "${dispatcher.packet.sweeper.interval-ms:30000}")
    public void sweepTimedOutPackets() {
        Instant now = Instant.now();
        List<PacketRecord> timedOut = packetRepository.findTimedOutPackets(now);

        if (timedOut.isEmpty()) {
            return;
        }

        for (PacketRecord packet : timedOut) {
            packet.setStatus("UNCLAIMED");
            packet.setClaimedByWorkerId(null);
            packet.setClaimedAt(null);
            packet.setTimeoutAt(null);
        }

        packetRepository.saveAll(timedOut);
        log.info("PacketTimeoutSweeper: reissued {} timed-out packet(s)", timedOut.size());
    }
}
