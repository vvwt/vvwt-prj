package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Public port for the per-tournament job-drain tick (DEC-64 D-3, DEC-64 D-12, DEC-35, DEC-58).
 *
 * <p>The drain service is the glue layer between the {@link WorkerRegistry} (which owns the
 * executor) and the {@link PhaseLifecycleOrchestrator} (which owns the per-job pipeline). A single
 * {@code drainNext()} call: (1) claims the next pending job via {@link PhaseLifecycleJobRepository}
 * CAS, (2) delegates to {@link PhaseLifecycleOrchestrator#tick(UUID)} for execution.
 *
 * <p>The worker thread loop calls {@code drainNext()} repeatedly until no PENDING rows remain, then
 * idles until the next enqueue signal or idle-timeout.
 *
 * <p>The sole implementation is {@link de.vvwt.tm.phaselifecycle.internal.DefaultJobDrainService},
 * which lands in E55S04.
 *
 * <p>Authorizing decisions: DEC-64 D-3 (worker tick loop), DEC-64 D-12 (TX granularity per
 * orchestrator step), DEC-35 (interface in module-root), DEC-58 (universal interface mandate).
 *
 * @since E55S01
 */
public interface JobDrainService {

    /**
     * Claims and executes the next pending job for the specified tournament (one job per call). If
     * no pending job exists, this method returns silently.
     *
     * @param tournamentId the tournament to drain
     */
    void drainNext(UUID tournamentId);
}
