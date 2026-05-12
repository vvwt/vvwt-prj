package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private executor for T-job-step-B of the Saga-Orchestrator drain pipeline (DEC-64 D-12).
 *
 * <p>T-job-step-B runs SlotOpt invocation (L3) + {@code phase.optimized=true} write + {@code
 * phase_lifecycle_job.status=COMPLETED} in one {@code REQUIRES_NEW} transaction. Extracted into a
 * separate Spring bean to allow {@link DefaultPhaseLifecycleOrchestrator} to call it through the
 * AOP proxy boundary (same reason as {@link OrchestratorStepAExecutor}).
 *
 * <h2>DEC-37 Clause B</h2>
 *
 * <p>{@code tournamentRepository.findByIdForUpdate(tournamentId)} is the FIRST DB read in this
 * transaction, per DEC-37 Clause B.
 *
 * <h2>L3-skip conditions (DEC-56 D-1 + DEC-59 Clause F)</h2>
 *
 * <ul>
 *   <li>When {@code tournament.optimize=false}: skip SlotOpt. {@code phase.optimized} stays {@code
 *       false}.
 *   <li>When {@code gameMode="siegerehrung"}: skip SlotOpt (DEC-59 Clause F). {@code
 *       phase.optimized} stays {@code false}.
 *   <li>Otherwise: invoke {@code SlotOptimizationClient.optimize(phaseId)} (L3), then set {@code
 *       phase.optimized=true}.
 * </ul>
 *
 * <h2>Job completion</h2>
 *
 * <p>After the L3 decision and write-back, the job is marked COMPLETED within the same TX.
 *
 * <h2>Cancel-flag clear (E55S05 / DEC-64 D-10)</h2>
 *
 * <p>After the L3 decision and write-back, {@link CancelFlagRegistry#clear(UUID)} is called for the
 * {@code tournamentId}. This ensures that a subsequent job enqueued under the same tournament is
 * not falsely detected as cancelled (AC-TEST-CANCEL-CLEAR-ON-COMPLETION). The clear is called
 * regardless of whether the job was actually cancelled — idempotent (clear on a non-set flag is a
 * no-op per {@link ConcurrentHashMap#remove}).
 *
 * <h2>DEC-66 D-2 last_job_state writes</h2>
 *
 * <ul>
 *   <li>Entry (before L3 decision): write {@code slot_opt_running}. Committed at TX end, so visible
 *       to external readers only after the step-B TX completes.
 *   <li>L3 success or L3-skipped (incl. BSF-apply on cancel per DEC-49 D-11a): write {@code idle} +
 *       {@code optimized=true/false} in same TX.
 * </ul>
 *
 * <p>Authorizing decisions: DEC-37 Clause B, DEC-49 D-11a, DEC-56 D-1, DEC-59 Clause F, DEC-64
 * D-10, DEC-64 D-12, DEC-64 D-16, DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-B-CLAIM,
 * AC-IMPL-LAST-JOB-STATE-STEP-B-SUCCESS.
 *
 * @since E55S04
 * @updated E55S05 (inject {@link CancelFlagRegistry}, call {@link CancelFlagRegistry#clear} after
 *     step-B)
 * @updated E55S08 (write {@code slot_opt_running} at entry; write {@code idle} at success / cancel
 *     per DEC-66 D-2)
 */
@Service
class OrchestratorStepBExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(OrchestratorStepBExecutor.class);

    static final String SIEGEREHRUNG_GAME_MODE = "siegerehrung";

    /** Written at step-B entry (before L3 decision) — per DEC-66 D-2. */
    static final String SLOT_OPT_RUNNING = "slot_opt_running";

    /** Written at step-B success (after L3 or L3-skip) and on cancel — per DEC-66 D-2. */
    static final String IDLE = "idle";

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final SlotOptimizationClient slotOptimizationClient;
    private final PhaseLifecycleJobRepository jobRepository;
    private final CancelFlagRegistry cancelFlagRegistry;

    OrchestratorStepBExecutor(
            @Qualifier("tmTournamentRepository") TournamentRepository tournamentRepository,
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            SlotOptimizationClient slotOptimizationClient,
            PhaseLifecycleJobRepository jobRepository,
            CancelFlagRegistry cancelFlagRegistry) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.slotOptimizationClient = slotOptimizationClient;
        this.jobRepository = jobRepository;
        this.cancelFlagRegistry = cancelFlagRegistry;
    }

    /**
     * Executes T-job-step-B for the given phase in a fresh {@code REQUIRES_NEW} transaction.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>DEC-37 Clause B: {@code findByIdForUpdate(tournamentId)} — per-tournament row-lock.
     *   <li>Load phase.
     *   <li>DEC-66 D-2: write {@code phase.last_job_state='slot_opt_running'} (before L3 decision).
     *   <li>If L3 should run ({@code tournament.optimize=true} AND NOT siegerehrung): invoke {@link
     *       SlotOptimizationClient#optimize(UUID)} and set {@code phase.optimized=true}.
     *   <li>DEC-66 D-2: write {@code phase.last_job_state='idle'} (step-B success terminal).
     *   <li>Persist phase.
     *   <li>Mark job COMPLETED.
     * </ol>
     *
     * @param tournamentId the tournament (for row-lock + optimize flag)
     * @param phaseId the phase to process
     * @param gameMode the game mode (from job row)
     * @param jobId the job row id (to mark completed)
     * @throws RuntimeException on any failure — T-step-B TX rolls back; job stays RUNNING
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void executeStepB(UUID tournamentId, UUID phaseId, String gameMode, UUID jobId) {
        // Step 1 (DEC-37 Clause B): acquire per-tournament row-lock — MUST be first DB read
        var tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        LOG.info(
                "OrchestratorStepBExecutor: START tournamentId={}, phaseId={}, gameMode={},"
                        + " jobId={}",
                tournamentId,
                phaseId,
                gameMode,
                jobId);

        // Step 2: load phase
        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Phase not found: " + phaseId));

        // Step 3 (DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-B-CLAIM): write slot_opt_running
        // before L3 decision — signals that SlotOpt is actively running for this phase.
        // Note: 'slot_opt_running' is committed inside this REQUIRES_NEW TX. External readers
        // see this write only after the full step-B TX commits (idle/failed terminal). Concurrent
        // in-TX observation requires a separate connection (H2 READ_COMMITTED default).
        phase.setLastJobState(SLOT_OPT_RUNNING);
        phaseRepository.save(phase);
        LOG.info(
                "OrchestratorStepBExecutor: last_job_state='slot_opt_running' phaseId={}", phaseId);

        // Step 4: L3 skip decision
        // DEC-56 D-1: L3 is skipped when tournament.optimize=false
        // DEC-59 Clause F: L3 is skipped for siegerehrung phases
        boolean isSiegerehrung = SIEGEREHRUNG_GAME_MODE.equalsIgnoreCase(gameMode);
        boolean shouldRunL3 = tournament.isOptimize() && !isSiegerehrung;

        if (shouldRunL3) {
            // Step 5a (L3): invoke SlotOpt — throws on failure; TX will roll back
            slotOptimizationClient.optimize(phaseId);
            // Step 5b: set phase.optimized=true (SlotOpt completed successfully)
            phase.setOptimized(true);
            LOG.info(
                    "OrchestratorStepBExecutor: L3 complete, phase.optimized=true," + " phaseId={}",
                    phaseId);
        } else {
            LOG.info(
                    "OrchestratorStepBExecutor: L3 skipped (optimize={}, isSiegerehrung={}),"
                            + " phase.optimized stays false, phaseId={}",
                    tournament.isOptimize(),
                    isSiegerehrung,
                    phaseId);
        }

        // Step 6 (DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-B-SUCCESS): write idle at step-B
        // success (L3 complete or L3 skipped — both are terminal-success for step-B).
        phase.setLastJobState(IDLE);

        // Step 7: persist phase (optimized flag + last_job_state='idle')
        phaseRepository.save(phase);

        // Step 8: mark job COMPLETED (within same TX — atomic with write-back)
        jobRepository.markCompleted(jobId);

        // Step 9 (DEC-64 D-10 / E55S05): clear in-memory cancel flag so a subsequent job under the
        // same tournament is NOT falsely detected as cancelled
        // (AC-TEST-CANCEL-CLEAR-ON-COMPLETION).
        // Called regardless of whether cancel was signalled — idempotent (remove on absent key is
        // a no-op per ConcurrentHashMap).
        cancelFlagRegistry.clear(tournamentId);

        LOG.info(
                "OrchestratorStepBExecutor: DONE tournamentId={}, phaseId={}, jobId={}",
                tournamentId,
                phaseId,
                jobId);
    }
}
