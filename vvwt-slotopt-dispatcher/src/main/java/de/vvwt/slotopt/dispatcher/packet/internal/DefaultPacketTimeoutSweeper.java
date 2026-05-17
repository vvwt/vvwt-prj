// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet.internal;

import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketTimeoutSweeper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link PacketTimeoutSweeper}.
 *
 * <p>Per DEC-35 / DEC-58 / DEC-72: implementation lives in {@code packet.internal}. All consumers
 * reference {@link PacketTimeoutSweeper} (the public interface in the {@code packet} root package),
 * never this class directly (DEC-36).
 *
 * <p>Per AC-PACKET-TIMEOUT-SWEEPER (E37S08): {@code @Scheduled} bean that scans for packets with
 * {@code status='CLAIMED' AND timeoutAt < now()} and resets them to {@code UNCLAIMED}, clearing
 * claim fields so they can be claimed by another worker.
 *
 * <p>The sweeper is designed to be testable via direct invocation of {@link
 * #sweepTimedOutPackets()} without relying on {@code @Scheduled} timing (per
 * AC-PACKET-TIMEOUT-SWEEPER).
 *
 * <p>AC-TEST-AOP-PROXY-SHIFT: Spring AOP {@code @Scheduled} proxy remains a JDK interface proxy via
 * the {@link PacketTimeoutSweeper} interface. The {@code sweepTimedOutPackets()} method is invoked
 * on the interface; the scheduled behaviour still fires on schedule (verified by the existing
 * {@link de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketTimeoutSweeperTest}
 * direct-invocation tests staying green).
 *
 * <p>Story: E37S08; AC-PACKET-TIMEOUT-SWEEPER; DEC-35.
 *
 * <p>Story: E57S03 — DEC-58/DEC-72 interface-mandate compliance (interface extraction).
 */
@Component
public class DefaultPacketTimeoutSweeper implements PacketTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(DefaultPacketTimeoutSweeper.class);

    private final PacketRepository packetRepository;

    public DefaultPacketTimeoutSweeper(PacketRepository packetRepository) {
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
    @Override
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
        log.info("DefaultPacketTimeoutSweeper: reissued {} timed-out packet(s)", timedOut.size());
    }
}
