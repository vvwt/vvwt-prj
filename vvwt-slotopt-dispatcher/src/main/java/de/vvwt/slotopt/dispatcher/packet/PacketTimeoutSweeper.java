// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

/**
 * Public interface for the background sweeper that periodically reissues timed-out claimed packets.
 *
 * <p>Consumers reference this interface; the implementation is {@link
 * de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketTimeoutSweeper} in the {@code
 * packet.internal} package (DEC-35, DEC-58, DEC-72).
 *
 * <p>Per AC-PACKET-TIMEOUT-SWEEPER: the sweeper scans for packets with {@code status='CLAIMED' AND
 * timeoutAt < now()} and resets them to {@code UNCLAIMED}, clearing claim fields so they can be
 * claimed by another worker.
 *
 * <p>Story: E37S08; AC-PACKET-TIMEOUT-SWEEPER; DEC-35.
 *
 * <p>Story: E57S03 — DEC-58/DEC-72 interface-mandate compliance (interface extraction).
 */
public interface PacketTimeoutSweeper {

    /**
     * Scans for timed-out {@code CLAIMED} packets and resets them to {@code UNCLAIMED}.
     *
     * <p>For each timed-out packet: sets {@code status = UNCLAIMED}, clears {@code
     * claimedByWorkerId}, {@code claimedAt}, and {@code timeoutAt}.
     */
    void sweepTimedOutPackets();
}
