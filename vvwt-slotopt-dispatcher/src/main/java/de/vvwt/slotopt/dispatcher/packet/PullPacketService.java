// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service interface for the pull-packet operation.
 *
 * <p>Per DEC-35: this interface lives in the public {@code packet} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.packet.internal.DefaultPullPacketService}) lives in {@code
 * packet.internal}.
 *
 * <p>All consumers (controllers, tests) type their dependency as {@code PullPacketService}, never
 * as the implementation class (DEC-36).
 *
 * <p>Story: E37S08; AC-PULL-PACKET-SERVICE; DEC-35, DEC-36
 */
public interface PullPacketService {

    /**
     * Atomically claims one unclaimed packet for the given worker.
     *
     * <p>Behavior per AC-PULL-PACKET-SERVICE:
     *
     * <ol>
     *   <li>Verifies the {@code workerId} is registered in the key registry. Unknown worker →
     *       throws {@link WorkerNotFoundException}.
     *   <li>Atomically claims the first {@code UNCLAIMED} packet (UPDATE with row-level lock). Sets
     *       {@code status = CLAIMED}, {@code claimedByWorkerId}, {@code claimedAt}, {@code
     *       timeoutAt} (= now + packet timeout).
     *   <li>Returns the claimed packet, or {@link Optional#empty()} if no unclaimed packets exist.
     * </ol>
     *
     * <p>Per Brief C-17: packet allocation is algorithm-blind in V1. The {@code capabilities} set
     * is recorded for future PQC routing but does NOT affect which packet is returned.
     *
     * @param workerId the UUID of the requesting worker
     * @param capabilities the set of algorithm identifiers the worker supports (informational, V1)
     * @return the claimed packet, or empty if none available
     * @throws WorkerNotFoundException if {@code workerId} is not registered
     */
    Optional<PacketRecord> claim(UUID workerId, Set<String> capabilities);
}
