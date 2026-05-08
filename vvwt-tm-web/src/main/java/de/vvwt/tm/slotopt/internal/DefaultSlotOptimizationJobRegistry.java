package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.OptimizationAlreadyInProgressException;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory implementation of {@link SlotOptimizationJobRegistry} backed by a {@link
 * ConcurrentHashMap} keyed by tournament UUID (E27S02 + E51S04, AC-JOB-REGISTRY-AUTHORED, DEC-49
 * D-11 + T-6, DEC-55 D-3a).
 *
 * <h2>E51S04 FIFO extension (DEC-55 D-3a)</h2>
 *
 * <p>A second {@link ConcurrentHashMap} ({@code fifoQueues}) holds a per-tournament {@link Deque}
 * of phase UUIDs. The head of the deque is the currently-queued phase for optimization; tail
 * entries are waiting.
 *
 * <h3>Concurrency model</h3>
 *
 * <p>Each per-tournament {@link Deque} is protected by a {@code synchronized} block on the deque
 * object itself. This ensures atomic enqueue-and-check (was-queue-empty) and atomic
 * dequeue-and-check (is-queue-now-empty) operations without a lock on the outer {@link
 * ConcurrentHashMap}.
 *
 * <p>The outer {@code ConcurrentHashMap#computeIfAbsent} ensures a single {@link Deque} instance is
 * created per tournament — subsequent operations synchronize on that instance.
 *
 * <h2>E27S02 backward compatibility (DEC-49 D-11)</h2>
 *
 * <p>{@link #getHandle(UUID)} returns the {@code activeJobs} entry — the handle registered for the
 * currently-running phase. The cancel controller (E27S02) calls this to obtain the handle for the
 * currently-running head phase.
 *
 * <h2>Durability (DEC-49 T-6)</h2>
 *
 * <p>The registry is scoped to the JVM lifetime. TM restart loses all in-flight job handles and the
 * FIFO queues. Recovery is handled by {@code JobQueueRecoveryService} (E51S07).
 *
 * <p>Per DEC-35 naming canon: implementation lives in {@code de.vvwt.tm.slotopt.internal},
 * interface in {@code de.vvwt.tm.slotopt} root package.
 *
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 T-6</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-55.md">DEC-55 D-3a</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E51S04.story.md">Story
 *     E51S04</a>
 */
@Service
public class DefaultSlotOptimizationJobRegistry implements SlotOptimizationJobRegistry {

    /** Handle map: tournamentId → currently-running job's handle (DEC-49 D-11). */
    private final ConcurrentHashMap<UUID, JobHandle> activeJobs = new ConcurrentHashMap<>();

    /**
     * FIFO queue map: tournamentId → ordered deque of phaseIds (DEC-55 D-3a). Each deque is
     * synchronized on itself for atomic enqueue/dequeue operations.
     */
    private final ConcurrentHashMap<UUID, Deque<UUID>> fifoQueues = new ConcurrentHashMap<>();

    // =========================================================================
    // E27S02 handle API (backward-compatible)
    // =========================================================================

    /** {@inheritDoc} */
    @Override
    public void register(UUID tournamentId, JobHandle handle) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        JobHandle existing = activeJobs.putIfAbsent(tournamentId, handle);
        if (existing != null) {
            throw new OptimizationAlreadyInProgressException(tournamentId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<JobHandle> getHandle(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        return Optional.ofNullable(activeJobs.get(tournamentId));
    }

    /** {@inheritDoc} */
    @Override
    public void complete(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        activeJobs.remove(tournamentId);
    }

    // =========================================================================
    // E51S04 FIFO extension (DEC-55 D-3a)
    // =========================================================================

    /** {@inheritDoc} */
    @Override
    public void enqueue(UUID tournamentId, UUID phaseId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        Deque<UUID> queue = fifoQueues.computeIfAbsent(tournamentId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(phaseId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getQueueDepth(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        Deque<UUID> queue = fifoQueues.get(tournamentId);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> peekQueue(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        Deque<UUID> queue = fifoQueues.get(tournamentId);
        if (queue == null) {
            return Optional.empty();
        }
        synchronized (queue) {
            return Optional.ofNullable(queue.peekFirst());
        }
    }

    /** {@inheritDoc} */
    @Override
    public UUID dequeueHead(UUID tournamentId) {
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        Deque<UUID> queue = fifoQueues.get(tournamentId);
        if (queue == null) {
            return null;
        }
        synchronized (queue) {
            return queue.pollFirst(); // returns null if empty — no NPE
            // (AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE)
        }
    }
}
