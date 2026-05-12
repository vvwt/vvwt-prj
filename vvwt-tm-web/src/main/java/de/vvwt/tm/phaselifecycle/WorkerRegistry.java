package de.vvwt.tm.phaselifecycle;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Public port for the per-tournament {@code ExecutorService} registry (DEC-64 D-3, DEC-35, DEC-58).
 *
 * <p>Maintains a {@code ConcurrentHashMap<UUID, ExecutorService>} of per-tournament single-thread
 * executors. Each tournament's worker serializes its own job queue (FIFO within tournament);
 * multiple tournaments execute concurrently (distinct workers, no cross-tournament blocking).
 *
 * <p>Worker lifecycle (per DEC-64 D-3): lazy-create on first enqueue; idle-timeout shutdown after
 * configurable idle period; JVM-shutdown {@code awaitTermination} up to a configurable timeout.
 * Full lifecycle implementation lands in E55S03.
 *
 * <p>The sole implementation is {@link de.vvwt.tm.phaselifecycle.internal.DefaultWorkerRegistry},
 * which lands in E55S03.
 *
 * <p>Authorizing decisions: DEC-64 D-3 (per-tournament single-thread executor), DEC-35 (interface
 * in module-root), DEC-58 (universal interface mandate).
 *
 * @since E55S01
 */
public interface WorkerRegistry {

    /**
     * Returns the {@code ExecutorService} for the given tournament, creating a new single-thread
     * executor lazily if none exists yet.
     *
     * @param tournamentId the tournament to get or create a worker for
     * @return the per-tournament executor (never null)
     */
    ExecutorService getOrCreate(UUID tournamentId);

    /**
     * Initiates an orderly shutdown of the worker for the given tournament. Running jobs complete;
     * no new jobs are accepted after this call.
     *
     * @param tournamentId the tournament whose worker should be shut down
     */
    void shutdownWorker(UUID tournamentId);

    /**
     * Initiates an orderly shutdown of ALL registered workers (called on JVM shutdown). Awaits
     * termination up to the configured timeout per DEC-64 D-3.
     */
    void shutdownAll();

    /**
     * Startup recovery hook (DEC-64 D-7): called after Spring context is fully initialized to:
     *
     * <ol>
     *   <li>Detect and handle corrupt RUNNING rows ({@code claimed_by = NULL}) → mark FAILED +
     *       WARN.
     *   <li>Reset stale RUNNING rows ({@code claimed_by != currentJvmId}) → PENDING.
     *   <li>Spawn a per-tournament worker for every tournament with non-COMPLETED jobs and submit a
     *       {@link de.vvwt.tm.phaselifecycle.JobDrainService#drainNext(java.util.UUID)} drain hint.
     * </ol>
     *
     * <p>This method implements the same path as the steady-state drain loop — no separate recovery
     * code-path exists (DEC-64 D-7 rationale: "recovery path is the SAME as the steady-state worker
     * loop").
     *
     * @param currentJvmId the JVM-instance identifier used in {@code claimed_by} (same value as in
     *     {@link de.vvwt.tm.phaselifecycle.internal.DefaultJobDrainService})
     * @since E55S07
     */
    void initOnStartup(String currentJvmId);
}
