// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry for active slot-optimization jobs (E27S02, AC-JOB-REGISTRY-AUTHORED, DEC-49 D-11 + T-6).
 *
 * <p><strong>E27S02 (original):</strong> Enforces at-most-one-active-job-per-tournament invariant
 * (AC-PER-TOURNAMENT-ISOLATION). The registry is in-memory ({@code ConcurrentHashMap<UUID,
 * JobHandle>}); job state is lost on TM restart (DEC-49 T-6 accepted trade-off).
 *
 * <p><strong>E55S06 (DEC-64 D-5/D-6):</strong> FIFO queue methods ({@code enqueue}, {@code
 * getQueueDepth}, {@code peekQueue}, {@code dequeueHead}) removed — the phase lifecycle DB queue
 * ({@code phase_lifecycle_job}) is now the authoritative FIFO (DEC-64 D-11). The FIFO-queue
 * in-memory implementation ({@code fifoQueues} field) and dependent beans ({@code
 * SlotOptJobScheduler}, {@code SlotOptFifoDispatcher}, {@code SlotOptInvocationListener}) are also
 * deleted. {@link #getHandle(UUID)} is reimplemented to be DB-primary (DEC-64 D-6): queries the
 * {@code phase_lifecycle_job} table for a RUNNING row; falls back to the in-memory {@code
 * activeJobs} map for CancellationToken access.
 *
 * <p>Per DEC-35 naming canon: this interface lives at the {@code de.vvwt.tm.slotopt} module root;
 * the implementation {@link de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationJobRegistry} lives
 * in {@code de.vvwt.tm.slotopt.internal}.
 *
 * @see JobHandle
 * @see OptimizationAlreadyInProgressException
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-5 + D-6</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E55S06.story.md">Story E55S06</a>
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
}
