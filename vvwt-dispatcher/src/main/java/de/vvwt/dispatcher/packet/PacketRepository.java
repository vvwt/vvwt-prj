package de.vvwt.dispatcher.packet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PacketRecord}.
 *
 * <p>Key queries:
 * <ul>
 *   <li>{@link #findAndLockNextPendingPacket()} — atomic claim via {@code SELECT FOR UPDATE SKIP LOCKED} (AC7)</li>
 *   <li>{@link #findTimedOutPackets(Instant)} — reissue sweeper input (AC8)</li>
 *   <li>{@link #countByJobIdAndStatus(UUID, String)} — used after decomposition to verify (AC1)</li>
 * </ul>
 *
 * <p>See Story E01S07 AC1, AC5, AC7, AC8.
 */
public interface PacketRepository extends JpaRepository<PacketRecord, UUID> {

    /**
     * Finds and exclusively locks the next {@code "pending"} packet from the highest-priority
     * {@code "ready"} job, using {@code SELECT FOR UPDATE SKIP LOCKED} for concurrency safety.
     *
     * <p>Two concurrent callers will never receive the same packet: SKIP LOCKED causes the
     * second caller to skip any row already locked by the first (AC7).
     *
     * <p>Ordering: highest job priority first, then oldest job first (FIFO within priority),
     * then lowest {@code rank_from} first (deterministic packet ordering within a job).
     *
     * @return the next available pending packet, or empty if no ready work exists
     */
    @Query(value = """
            SELECT p.* FROM packets p
            JOIN jobs j ON p.job_id = j.job_id
            WHERE p.status = 'pending'
              AND j.status = 'ready'
            ORDER BY j.priority DESC, j.submitted_at ASC, p.rank_from ASC
            LIMIT 1
            FOR UPDATE OF p SKIP LOCKED
            """, nativeQuery = true)
    Optional<PacketRecord> findAndLockNextPendingPacket();

    /**
     * Finds all {@code "assigned"} packets whose {@code assigned_at} is before {@code cutoff}.
     *
     * <p>Used by the timeout sweeper to reissue packets that have been held by a worker
     * longer than the configured packet timeout (AC8).
     *
     * @param cutoff the threshold; packets assigned before this time are considered timed out
     * @return list of timed-out assigned packets (may be empty)
     */
    @Query("SELECT p FROM PacketRecord p WHERE p.status = 'assigned' AND p.assignedAt < :cutoff")
    List<PacketRecord> findTimedOutPackets(Instant cutoff);

    /**
     * Counts packets for a given job in a given status.
     *
     * <p>Used after decomposition to verify the packet count and after sweeping to
     * assess job-level failure state.
     *
     * @param jobId  the job UUID
     * @param status the status to count (e.g. {@code "pending"}, {@code "done"}, {@code "failed"})
     * @return count of matching packets
     */
    long countByJobIdAndStatus(UUID jobId, String status);
}
