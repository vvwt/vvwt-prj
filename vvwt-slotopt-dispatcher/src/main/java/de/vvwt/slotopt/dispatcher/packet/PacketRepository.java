// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC repository for {@link PacketRecord}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} interfaces ARE the port by definition — no
 * separate public interface wrapper is needed or allowed (Spring-Data carve-out).
 *
 * <p>Custom finders per AC-PACKET-REPOSITORY:
 *
 * <ul>
 *   <li>{@link #findByPacketId(UUID)} — lookup by external UUID
 *   <li>{@link #findByJobId(UUID)} — all packets for a job
 *   <li>{@link #findFirstByStatusOrderById(String)} — atomic unclaimed-packet selection for claim
 *       operations
 *   <li>{@link #findTimedOutPackets(Instant)} — sweep query for timeout sweeper
 * </ul>
 *
 * <p>Story: E37S08; AC-PACKET-REPOSITORY; DEC-35
 */
public interface PacketRepository extends CrudRepository<PacketRecord, Long> {

    /**
     * Finds a {@link PacketRecord} by its externally-visible packet UUID.
     *
     * @param packetId the external packet UUID
     * @return the packet record if found, otherwise empty
     */
    Optional<PacketRecord> findByPacketId(UUID packetId);

    /**
     * Finds all {@link PacketRecord}s for a given job UUID.
     *
     * @param jobId the job UUID
     * @return list of packets belonging to the job (may be empty)
     */
    List<PacketRecord> findByJobId(UUID jobId);

    /**
     * Finds the first packet with the given status, ordered by ID ascending.
     *
     * <p>Used by the atomic packet-claim path: {@link
     * de.vvwt.slotopt.dispatcher.packet.internal.DefaultPullPacketService} selects one {@code
     * UNCLAIMED} packet for claiming.
     *
     * @param status the status to filter by
     * @return the lowest-ID packet with the given status, or empty if none
     */
    Optional<PacketRecord> findFirstByStatusOrderById(String status);

    /**
     * Finds all CLAIMED packets whose {@code timeoutAt} is before the given instant.
     *
     * <p>Used by {@link de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketTimeoutSweeper} to
     * identify packets to reissue.
     *
     * @param now reference instant; packets with {@code timeoutAt < now} are timed out
     * @return list of timed-out claimed packets
     */
    @Query("SELECT * FROM packet WHERE status = 'CLAIMED' AND timeout_at < :now")
    List<PacketRecord> findTimedOutPackets(@Param("now") Instant now);
}
