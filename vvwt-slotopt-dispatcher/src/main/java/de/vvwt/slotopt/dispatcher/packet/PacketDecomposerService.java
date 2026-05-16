// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import de.vvwt.slotopt.dispatcher.job.JobRecord;
import java.util.List;

/**
 * Service interface for decomposing a {@link JobRecord} into a list of {@link PacketRecord}s.
 *
 * <p>Per DEC-35: this interface lives in the public {@code packet} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketDecomposerService}) lives in
 * {@code packet.internal}.
 *
 * <p>All consumers (controllers, tests, other services) type their dependency as {@code
 * PacketDecomposerService}, never as the implementation class (DEC-36).
 *
 * <p>Story: E37S08; AC-PACKET-DECOMPOSER-SERVICE; DEC-35, DEC-36
 */
public interface PacketDecomposerService {

    /**
     * Decomposes a {@link JobRecord} into a list of {@link PacketRecord}s.
     *
     * <p>Behavior per AC-PACKET-DECOMPOSER-SERVICE + spec section (b) §Packet Decomposition:
     *
     * <ol>
     *   <li>Parses the {@code job.jobDefJson} to extract {@code n} and the {@code
     *       CanonicalPhaseDef}
     *   <li>Computes the total permutation count: {@code n!}
     *   <li>If {@code n! < 4 × permsPerPacket}: splits into exactly 4 packets
     *   <li>Else: splits into {@code ceil(n! / permsPerPacket)} packets, each covering a {@code
     *       [rankFrom, rankTo)} interval of the lex-order permutation space
     *   <li>Each packet is assigned a new UUID, inherits the {@code jobId}, carries the rank
     *       interval in {@code packetPayloadJson}, and has {@code status = "UNCLAIMED"}
     * </ol>
     *
     * <p>The returned records are NOT saved to the database — the caller is responsible for
     * persistence.
     *
     * @param job the job record to decompose; must not be {@code null}; {@code jobDefJson} must be
     *     a valid serialized {@link de.vvwt.slotopt.worker.types.JobDef} JSON
     * @return an ordered list of packet records spanning the full permutation space {@code [0, n!)}
     * @throws IllegalArgumentException if {@code job} is null or {@code jobDefJson} cannot be
     *     parsed
     */
    List<PacketRecord> decompose(JobRecord job);
}
