package de.vvwt.tm.slotopt;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry for active slot-optimization jobs and the per-tournament FIFO queue (E27S02 + E51S04,
 * AC-JOB-REGISTRY-AUTHORED, DEC-49 D-11 + T-6, DEC-55 D-3a).
 *
 * <p><strong>E27S02 (original):</strong> Enforces at-most-one-active-job-per-tournament invariant
 * (AC-PER-TOURNAMENT-ISOLATION). The registry is in-memory ({@code ConcurrentHashMap<UUID,
 * JobHandle>}); job state is lost on TM restart (DEC-49 T-6 accepted trade-off).
 *
 * <p><strong>E51S04 extension (DEC-55 D-3a):</strong> Adds a per-tournament FIFO queue of {@link
 * UUID} phase IDs. Multiple phases for the same tournament are serialized: only the head element is
 * actively optimizing at any time. {@link #getHandle(UUID)} remains backward-compatible — it
 * returns the head element's {@link JobHandle}.
 *
 * <p>Per DEC-35 naming canon: this interface lives at the {@code de.vvwt.tm.slotopt} module root;
 * the implementation {@link de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationJobRegistry} lives
 * in {@code de.vvwt.tm.slotopt.internal}.
 *
 * @see JobHandle
 * @see OptimizationAlreadyInProgressException
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-55.md">DEC-55 D-3a</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E51S04.story.md">Story E51S04</a>
 */
public interface SlotOptimizationJobRegistry {

    /**
     * Registers a new job handle for the given tournament.
     *
     * <p>Throws {@link OptimizationAlreadyInProgressException} if an active job is already
     * registered for {@code tournamentId}.
     *
     * @param tournamentId the tournament for which the optimization is starting
     * @param handle the job handle to register
     * @throws OptimizationAlreadyInProgressException if a job is already active for this tournament
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    void register(UUID tournamentId, JobHandle handle);

    /**
     * Retrieves the active job handle for the FIFO head element of the given tournament, if any.
     *
     * <p>Backward-compatible with the E27S02 single-handle contract: the cancel controller ({@link
     * de.vvwt.tm.slotopt.SlotOptimizationJobRegistry}) calls this to obtain the handle for the
     * currently-running (head) phase and cancels it. Phase IDs queued behind the head are not yet
     * running and cannot be cancelled via this method (returns HTTP 409 from the cancel controller
     * — see DEC-49 D-11).
     *
     * @param tournamentId the tournament UUID
     * @return the active handle for the FIFO head, or {@link Optional#empty()} if no job is active
     * @throws IllegalArgumentException if {@code tournamentId} is {@code null}
     */
    Optional<JobHandle> getHandle(UUID tournamentId);

    /**
     * Marks the job for the given tournament as complete and removes it from the registry.
     *
     * <p>Idempotent: calling this when no job is active for the tournament is a no-op.
     *
     * @param tournamentId the tournament whose job has completed
     * @throws IllegalArgumentException if {@code tournamentId} is {@code null}
     */
    void complete(UUID tournamentId);

    // =========================================================================
    // E51S04 FIFO extension (DEC-55 D-3a)
    // =========================================================================

    /**
     * Enqueues a phase ID into the per-tournament FIFO queue for slot-optimization (DEC-55 D-3a).
     *
     * <p>Thread-safe. Multiple enqueues for the same tournament accumulate in FIFO order.
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @param phaseId the phase UUID to enqueue; must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    void enqueue(UUID tournamentId, UUID phaseId);

    /**
     * Returns the number of phase IDs currently in the FIFO queue for the given tournament.
     *
     * <p>Returns {@code 0} if no queue exists for the tournament.
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @return the queue depth; {@code 0} if no phases are queued
     * @throws IllegalArgumentException if {@code tournamentId} is {@code null}
     */
    int getQueueDepth(UUID tournamentId);

    /**
     * Returns the head phase ID from the FIFO queue without removing it.
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @return the head phase ID, or {@link Optional#empty()} if the queue is empty
     * @throws IllegalArgumentException if {@code tournamentId} is {@code null}
     */
    Optional<UUID> peekQueue(UUID tournamentId);

    /**
     * Removes and returns the head phase ID from the FIFO queue.
     *
     * <p>Returns {@code null} if the queue is empty (no exception — callers must handle {@code
     * null} gracefully, e.g., {@code SlotOptJobScheduler.onJobCompleted}).
     *
     * @param tournamentId the tournament UUID; must not be {@code null}
     * @return the dequeued phase ID, or {@code null} if the queue was empty
     * @throws IllegalArgumentException if {@code tournamentId} is {@code null}
     */
    UUID dequeueHead(UUID tournamentId);
}
