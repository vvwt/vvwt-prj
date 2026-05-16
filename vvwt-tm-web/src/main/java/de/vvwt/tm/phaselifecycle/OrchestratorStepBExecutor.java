package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Interface for the T-job-step-B executor of the Saga-Orchestrator drain pipeline (DEC-64 D-12).
 *
 * <p>T-job-step-B runs SlotOpt invocation (L3) + {@code phase.optimized=true} write + {@code
 * phase_lifecycle_job.status=COMPLETED} in one {@code REQUIRES_NEW} transaction. Extracted as a
 * separate Spring bean to allow {@link de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator} to
 * call it through the AOP proxy boundary.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface OrchestratorStepBExecutor {

    /**
     * Executes T-job-step-B for the given phase in a fresh {@code REQUIRES_NEW} transaction.
     *
     * @param tournamentId the tournament (for row-lock + optimize flag)
     * @param phaseId the phase to process
     * @param gameMode the game mode (from job row)
     * @param jobId the job row id (to mark completed)
     * @throws RuntimeException on any failure — T-step-B TX rolls back; job stays RUNNING
     */
    void executeStepB(UUID tournamentId, UUID phaseId, String gameMode, UUID jobId);
}
