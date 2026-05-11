package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Public port for the cooperative cancel-flag registry (DEC-64 D-10, DEC-35, DEC-58).
 *
 * <p>Maintains an in-memory mirror of the DB {@code phase_lifecycle_job.cancelled} flag. The L3
 * permutation loop ({@link de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService}) polls
 * this registry at its polling points. When a cancel is requested (via {@link
 * #requestCancel(UUID)}), the loop observes the flag on its next check and applies Best-So-Far
 * semantics per DEC-49 D-11a + DEC-64 D-16.
 *
 * <p>The registry is per-tournament: cancelling tournament T affects only T's in-flight job.
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultCancelFlagRegistry}, which lands in E55S05.
 *
 * <p>Authorizing decisions: DEC-49 D-11 (operator-cancel per-tournament scope), DEC-49 D-11a
 * (Best-So-Far on cancel), DEC-64 D-10 (cooperative cancel flag), DEC-64 D-16 (cancel-completion
 * invariant), DEC-35 (interface in module-root), DEC-58 (universal interface mandate).
 *
 * @since E55S01
 */
public interface CancelFlagRegistry {

    /**
     * Signals a cancel request for the given tournament. The in-flight L3 loop observes this flag
     * at its next polling point and applies Best-So-Far semantics.
     *
     * @param tournamentId the tournament to cancel
     */
    void requestCancel(UUID tournamentId);

    /**
     * Returns {@code true} if a cancel has been requested for the given tournament and not yet
     * cleared.
     *
     * @param tournamentId the tournament to check
     * @return true if a cancel is pending
     */
    boolean isCancelled(UUID tournamentId);

    /**
     * Clears the cancel flag for the given tournament after the cancel has been fully processed
     * (Best-So-Far applied + job COMPLETED per DEC-64 D-16).
     *
     * @param tournamentId the tournament whose flag to clear
     */
    void clear(UUID tournamentId);
}
