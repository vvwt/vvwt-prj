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
 * <p>Authorizing decisions: DEC-37 Clause B, DEC-49 D-11a, DEC-56 D-1, DEC-59 Clause F, DEC-64
 * D-10, DEC-64 D-12, DEC-64 D-16.
 *
 * @since E55S04
 * @updated E55S05 (inject {@link CancelFlagRegistry}, call {@link CancelFlagRegistry#clear} after
 *     step-B)
 */
@Service
class OrchestratorStepBExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(OrchestratorStepBExecutor.class);

    static final String SIEGEREHRUNG_GAME_MODE = "siegerehrung";

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
     *   <li>If L3 should run ({@code tournament.optimize=true} AND NOT siegerehrung): invoke {@link
     *       SlotOptimizationClient#optimize(UUID)} and set {@code phase.optimized=true}.
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

        // Step 3: L3 skip decision
        // DEC-56 D-1: L3 is skipped when tournament.optimize=false
        // DEC-59 Clause F: L3 is skipped for siegerehrung phases
        boolean isSiegerehrung = SIEGEREHRUNG_GAME_MODE.equalsIgnoreCase(gameMode);
        boolean shouldRunL3 = tournament.isOptimize() && !isSiegerehrung;

        if (shouldRunL3) {
            // Step 3a (L3): invoke SlotOpt — throws on failure; TX will roll back
            slotOptimizationClient.optimize(phaseId);
            // Step 3b: set phase.optimized=true (SlotOpt completed successfully)
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

        // Step 4: persist phase (optimized flag)
        phaseRepository.save(phase);

        // Step 5: mark job COMPLETED (within same TX — atomic with write-back)
        jobRepository.markCompleted(jobId);

        // Step 6 (DEC-64 D-10 / E55S05): clear in-memory cancel flag so a subsequent job under the
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
