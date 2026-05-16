package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.OrchestratorStepAExecutor;
import de.vvwt.tm.phaselifecycle.OrchestratorStepBExecutor;
import de.vvwt.tm.phaselifecycle.PhaseLastJobStateWriter;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobDetails;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleOrchestrator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Saga-Orchestrator for the phase-lifecycle drain pipeline (DEC-64 D-1, DEC-64 D-12).
 *
 * <p>Called by {@link JobDrainService} after a successful CAS claim. Runs the two-step pipeline:
 *
 * <ol>
 *   <li><b>T-job-step-A</b> ({@link OrchestratorStepAExecutor#executeStepA}): MatchGen (L1) +
 *       round-assignment (L2) + {@code PENDING→PREPARED} transition, in one {@code REQUIRES_NEW}
 *       TX. DEC-37 Clause B: {@code findByIdForUpdate} as first read.
 *   <li><b>T-job-step-B</b> ({@link OrchestratorStepBExecutor#executeStepB}): SlotOpt (L3, if
 *       conditions met) + {@code phase.optimized=true} write-back + {@code job.status=COMPLETED},
 *       in a separate {@code REQUIRES_NEW} TX. DEC-37 Clause B: {@code findByIdForUpdate} as first
 *       read.
 * </ol>
 *
 * <h2>TX isolation (DEC-64 D-12)</h2>
 *
 * <p>The two steps run in independent {@code REQUIRES_NEW} transactions. A step-A failure leaves
 * the job {@code RUNNING} and rolls back any partial match inserts. A step-B failure rolls back
 * SlotOpt apply + optimized write; matches from step-A are preserved.
 *
 * <h2>Self-invocation avoidance</h2>
 *
 * <p>The {@code @Transactional} methods are on {@link OrchestratorStepAExecutor} and {@link
 * OrchestratorStepBExecutor} — separate Spring beans. This ensures the AOP proxy intercepts the
 * {@code @Transactional} boundary correctly (no self-invocation bypass).
 *
 * <h2>DEC-37 Clause B</h2>
 *
 * <p>Both step-A and step-B call {@code tournamentRepository.findByIdForUpdate(tournamentId)} as
 * the first DB read of their respective transactions. See executor Javadocs.
 *
 * <h2>DEC-66 D-2 failure writer (E55S08)</h2>
 *
 * <p>When step-A or step-B throws, {@link PhaseLastJobStateWriter#writeFailedState(UUID)} is called
 * in a {@code REQUIRES_NEW} TX — commits independently of the rolled-back step TX — then the
 * exception is re-thrown so the job stays {@code RUNNING} per DEC-64 D-12.
 *
 * <p>Authorizing decisions: DEC-37 Clause B, DEC-44, DEC-56 D-1, DEC-59 Clause F, DEC-64 D-1,
 * DEC-64 D-9, DEC-64 D-12, DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED.
 *
 * @since E55S04
 * @updated E55S08 (inject {@link PhaseLastJobStateWriter}; write {@code failed} on step-A or step-B
 *     exception per DEC-66 D-2)
 * @see OrchestratorStepAExecutor
 * @see OrchestratorStepBExecutor
 */
@Service("phaseLifecycleOrchestrator")
public class DefaultPhaseLifecycleOrchestrator implements PhaseLifecycleOrchestrator {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultPhaseLifecycleOrchestrator.class);

    private final PhaseLifecycleJobRepository jobRepository;
    private final OrchestratorStepAExecutor stepAExecutor;
    private final OrchestratorStepBExecutor stepBExecutor;
    private final PhaseLastJobStateWriter failureWriter;

    public DefaultPhaseLifecycleOrchestrator(
            PhaseLifecycleJobRepository jobRepository,
            OrchestratorStepAExecutor stepAExecutor,
            OrchestratorStepBExecutor stepBExecutor,
            PhaseLastJobStateWriter failureWriter) {
        this.jobRepository = jobRepository;
        this.stepAExecutor = stepAExecutor;
        this.stepBExecutor = stepBExecutor;
        this.failureWriter = failureWriter;
    }

    /**
     * Executes one drain step for the specified tournament.
     *
     * <p>Resolves the job details for the already-claimed job, then delegates to step-A and step-B
     * executors in sequence. If no job details are found (defensive — should not occur in normal
     * operation), the method returns without action.
     *
     * <p>The {@code tournamentId} is used as a discriminator to find the active RUNNING job for
     * this tournament (sourced from the CAS claim in {@link DefaultJobDrainService}).
     *
     * @param jobId the already-claimed job row id
     */
    void executeClaimed(UUID jobId) {
        PhaseLifecycleJobDetails details = jobRepository.findJobDetailsById(jobId).orElse(null);

        if (details == null) {
            LOG.warn(
                    "DefaultPhaseLifecycleOrchestrator: jobId={} not found after claim —"
                            + " no-op (defensive)",
                    jobId);
            return;
        }

        UUID tournamentId = details.tournamentId();
        UUID phaseId = details.phaseId();
        String gameMode = details.gameMode();

        LOG.info(
                "DefaultPhaseLifecycleOrchestrator: BEGIN jobId={}, tournamentId={},"
                        + " phaseId={}, gameMode={}",
                jobId,
                tournamentId,
                phaseId,
                gameMode);

        // T-job-step-A: MatchGen (L1) + L2 + PENDING→PREPARED — REQUIRES_NEW TX
        try {
            stepAExecutor.executeStepA(tournamentId, phaseId, gameMode);
        } catch (RuntimeException e) {
            // DEC-66 D-2 / AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED: write failed in REQUIRES_NEW
            // TX so it commits independently of the rolled-back step-A TX. Re-throw: job stays
            // RUNNING.
            LOG.warn(
                    "DefaultPhaseLifecycleOrchestrator: step-A failure — writing"
                            + " last_job_state='failed' for phaseId={}, jobId={}",
                    phaseId,
                    jobId,
                    e);
            failureWriter.writeFailedState(phaseId);
            throw e;
        }

        // T-job-step-B: SlotOpt (L3, conditional) + optimized write + COMPLETED — REQUIRES_NEW TX
        try {
            stepBExecutor.executeStepB(tournamentId, phaseId, gameMode, jobId);
        } catch (RuntimeException e) {
            // DEC-66 D-2 / AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED: write failed in REQUIRES_NEW
            // TX so it commits independently of the rolled-back step-B TX. Re-throw: job stays
            // RUNNING.
            LOG.warn(
                    "DefaultPhaseLifecycleOrchestrator: step-B failure — writing"
                            + " last_job_state='failed' for phaseId={}, jobId={}",
                    phaseId,
                    jobId,
                    e);
            failureWriter.writeFailedState(phaseId);
            throw e;
        }

        LOG.info(
                "DefaultPhaseLifecycleOrchestrator: DONE jobId={}, tournamentId={}, phaseId={}",
                jobId,
                tournamentId,
                phaseId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation of {@link PhaseLifecycleOrchestrator#tick(UUID)} is provided for
     * backward compatibility with the interface contract. The primary entry point for the
     * Saga-Orchestrator pipeline is {@link DefaultJobDrainService#drainNext(UUID)}, which performs
     * the CAS claim and then calls {@link #executeClaimed(UUID)} directly.
     *
     * <p>When called directly with a {@code tournamentId}, this method claims the next pending job
     * for the tournament and executes it. Returns silently if no pending job exists.
     */
    @Override
    public void tick(UUID tournamentId) {
        // Find + CAS-claim + execute — mirrors DefaultJobDrainService.drainNext() for callers
        // that hold only the tournamentId (not a pre-claimed jobId).
        var jobIdOpt = jobRepository.findNextPendingJobIdForTournament(tournamentId);
        if (jobIdOpt.isEmpty()) {
            LOG.debug(
                    "DefaultPhaseLifecycleOrchestrator.tick: no pending job for tournamentId={}",
                    tournamentId);
            return;
        }
        UUID jobId = jobIdOpt.get();
        boolean claimed = jobRepository.tryClaim(jobId, "orchestrator-tick");
        if (!claimed) {
            LOG.info(
                    "DefaultPhaseLifecycleOrchestrator.tick: CAS claim lost for jobId={} —"
                            + " concurrent worker claimed it",
                    jobId);
            return;
        }
        executeClaimed(jobId);
    }
}
