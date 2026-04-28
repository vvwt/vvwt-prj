package de.vvwt.tm.slotopt;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry for active slot-optimization jobs, keyed by tournament UUID (E27S02,
 * AC-JOB-REGISTRY-AUTHORED, DEC-49 D-11 + T-6).
 *
 * <p>Enforces at-most-one-active-job-per-tournament invariant (AC-PER-TOURNAMENT-ISOLATION). The
 * registry is in-memory ({@code ConcurrentHashMap<UUID, JobHandle>}); job state is lost on TM
 * restart (DEC-49 T-6 accepted trade-off).
 *
 * <p>Per DEC-35 naming canon: this interface lives at the {@code de.vvwt.tm.slotopt} module root;
 * the implementation {@link de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationJobRegistry} lives
 * in {@code de.vvwt.tm.slotopt.internal}.
 *
 * @see JobHandle
 * @see OptimizationAlreadyInProgressException
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
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
     * Retrieves the active job handle for the given tournament, if any.
     *
     * @param tournamentId the tournament UUID
     * @return the active handle, or {@link Optional#empty()} if no job is active
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
