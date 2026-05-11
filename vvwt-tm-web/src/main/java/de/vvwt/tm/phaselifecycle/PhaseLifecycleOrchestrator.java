package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Public port for the phase-lifecycle Saga-Orchestrator drain step (DEC-64 D-1, DEC-35, DEC-58).
 *
 * <p>The orchestrator owns the imperative workflow: read a pending job from the {@code
 * phase_lifecycle_job} queue, invoke MatchGen → L1 → L2 → SlotOpt synchronously, write the result
 * back. One {@code tick()} call processes at most one pending job for the specified tournament.
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleOrchestrator}, which lands in E55S04.
 *
 * <p>Authorizing decisions: DEC-64 D-1 (Saga-Orchestrator pattern), DEC-64 D-14 (bean enumeration),
 * DEC-35 (interface in module-root), DEC-58 (universal interface mandate).
 *
 * @since E55S01
 */
public interface PhaseLifecycleOrchestrator {

    /**
     * Executes one drain step for the specified tournament: claims the next pending job and runs
     * the full pipeline (MatchGen → L1+L2 → SlotOpt → write-back) synchronously.
     *
     * <p>This is the entry point for the per-tournament single-thread worker (DEC-64 D-3). Full
     * implementation lands in E55S04.
     *
     * @param tournamentId the tournament whose job queue is drained
     */
    void tick(UUID tournamentId);
}
