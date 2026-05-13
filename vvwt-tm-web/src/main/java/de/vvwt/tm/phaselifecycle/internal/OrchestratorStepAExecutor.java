package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.RoundAssignmentService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private executor for T-job-step-A of the Saga-Orchestrator drain pipeline (DEC-64 D-12).
 *
 * <p>T-job-step-A runs MatchGen (L1) + round-assignment (L2) + PENDING→PREPARED transition in one
 * {@code REQUIRES_NEW} transaction. Extracted into a separate Spring bean so that {@link
 * DefaultPhaseLifecycleOrchestrator} can call it through the AOP proxy boundary — necessary because
 * {@code @Transactional} self-invocations within the same bean bypass the proxy.
 *
 * <h2>DEC-37 Clause B</h2>
 *
 * <p>{@code tournamentRepository.findByIdForUpdate(tournamentId)} is the FIRST DB read in this
 * transaction, acquiring the per-tournament pessimistic row-lock per DEC-37 Clause B. This
 * serializes concurrent T-step-A executions for the same tournament.
 *
 * <h2>Siegerehrung handling (DEC-59 Clause E)</h2>
 *
 * <p>The {@code gameMode} parameter is sourced from the already-committed job row (set at enqueue
 * time). When {@code gameMode="siegerehrung"}, L1 is invoked with the {@code "siegerehrung"}
 * generator key (producing 0 matches). L2 is a no-op (0 matches). The PENDING→PREPARED transition
 * proceeds normally.
 *
 * <p>Authorizing decisions: DEC-37 Clause B, DEC-55 D-3, DEC-56 D-1 (L1+L2 always run), DEC-59
 * Clause E, DEC-64 D-12, DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-A-DONE-OPTIMIZE-TRUE-QUEUED,
 * AC-IMPL-LAST-JOB-STATE-STEP-A-DONE-OPTIMIZE-FALSE-OR-SIEGEREHRUNG.
 *
 * @since E55S04
 * @updated E55S08 (inject {@link PhaseRepository}; write {@code slot_opt_queued} or {@code idle} at
 *     step-A-done per DEC-66 D-2)
 */
@Service
class OrchestratorStepAExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(OrchestratorStepAExecutor.class);

    /** Written when optimize=TRUE AND non-siegerehrung — per DEC-66 D-2 row 3. */
    static final String SLOT_OPT_QUEUED = "slot_opt_queued";

    /** Written at step-A terminal paths (optimize=FALSE or siegerehrung) — per DEC-66 D-2 row 2. */
    static final String IDLE = "idle";

    static final String SIEGEREHRUNG_GAME_MODE = "siegerehrung";

    private final TournamentRepository tournamentRepository;
    private final PhasePreparationService phasePreparationService;
    private final RoundAssignmentService roundAssignmentService;
    private final PhaseLifecycleService phaseLifecycleService;
    private final PhaseRepository phaseRepository;
    private final int fallbackFieldCount;

    OrchestratorStepAExecutor(
            @Qualifier("tmTournamentRepository") TournamentRepository tournamentRepository,
            @Qualifier("tmPhasePreparationService") PhasePreparationService phasePreparationService,
            RoundAssignmentService roundAssignmentService,
            @Qualifier("tmPhaseLifecycleService") PhaseLifecycleService phaseLifecycleService,
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            @Value("${tm.slotopt.fallback.field-count:3}") int fallbackFieldCount) {
        this.tournamentRepository = tournamentRepository;
        this.phasePreparationService = phasePreparationService;
        this.roundAssignmentService = roundAssignmentService;
        this.phaseLifecycleService = phaseLifecycleService;
        this.phaseRepository = phaseRepository;
        this.fallbackFieldCount = fallbackFieldCount;
    }

    /**
     * Executes T-job-step-A for the given phase in a fresh {@code REQUIRES_NEW} transaction.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>DEC-37 Clause B: {@code findByIdForUpdate(tournamentId)} — acquires per-tournament
     *       pessimistic row-lock as the FIRST DB read.
     *   <li>L1: {@link PhasePreparationService#generateMatches(UUID, String)} with the job's {@code
     *       gameMode} as the generator key (supports "roundRobin", "siegerehrung", etc.).
     *   <li>Resolve {@code fieldCount} (tournament.fieldCount with fallback per DEC-55 D-13).
     *   <li>L2: {@link RoundAssignmentService#assignRoundsAndFields(UUID, int)} — a no-op if 0
     *       matches (siegerehrung / DEC-56 D-1 vacuous case).
     *   <li>Advance phase lifecycle: {@code PENDING → PREPARED} via {@code "match-gen-done"}.
     * </ol>
     *
     * @param tournamentId the tournament (for row-lock + fieldCount)
     * @param phaseId the phase to process
     * @param gameMode the generator key (from job row, set at enqueue time)
     * @throws RuntimeException on any failure — T-step-A TX rolls back; job stays RUNNING
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void executeStepA(UUID tournamentId, UUID phaseId, String gameMode) {
        // Step 1 (DEC-37 Clause B): acquire per-tournament row-lock — MUST be first DB read
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        LOG.info(
                "OrchestratorStepAExecutor: START tournamentId={}, phaseId={}, gameMode={}",
                tournamentId,
                phaseId,
                gameMode);

        // Step 2 (L1): generate matches using the job's gameMode as the generator key
        // DEC-56 D-1: L1+L2 ALWAYS run regardless of tournament.optimize
        phasePreparationService.generateMatches(phaseId, gameMode);

        // Step 3: resolve fieldCount with DEC-55 D-13 fallback
        int resolvedFieldCount = tournament.getFieldCount();
        if (resolvedFieldCount < 1) {
            resolvedFieldCount = fallbackFieldCount;
            LOG.info(
                    "OrchestratorStepAExecutor: tournament.fieldCount={} → D-13 fallback {}",
                    tournament.getFieldCount(),
                    resolvedFieldCount);
        }

        // Step 4 (L2): assign rounds and fields
        // DEC-56 D-1 + siegerehrung: if 0 matches, assignRoundsAndFields is a no-op
        roundAssignmentService.assignRoundsAndFields(phaseId, resolvedFieldCount);

        // Step 5: transition phase PENDING → PREPARED
        phaseLifecycleService.transition(phaseId, Phase.PhaseStatus.PREPARED, "match-gen-done");

        // Step 6 (DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-STEP-A-DONE): write lastJobState at step-A
        // terminal.
        // optimize=TRUE AND non-siegerehrung → slot_opt_queued (step-B will be enqueued by
        // orchestrator)
        // optimize=FALSE OR siegerehrung → idle (terminal: no step-B enqueued)
        // E55S09 H-B structural fix (DEFENSE-IN-DEPTH per AC-FIX-H-B-ORCHESTRATOR-STEP-A-COLUMN-
        // SCOPED): use column-scoped updateLastJobState instead of findById+setLastJobState+save.
        // This call-site is already structurally protected by the preceding transition() guard at
        // step 5 (which throws IllegalStateException on a non-PENDING phase, rolling back the TX
        // before this write executes). The fix is DEFENSE-IN-DEPTH: eliminates the latent producer
        // surface if the transition-guard upstream is ever weakened.
        boolean isSiegerehrung = SIEGEREHRUNG_GAME_MODE.equalsIgnoreCase(gameMode);
        boolean stepBWillBeEnqueued = tournament.isOptimize() && !isSiegerehrung;
        String stepADoneState = stepBWillBeEnqueued ? SLOT_OPT_QUEUED : IDLE;
        // Column-scoped write: UPDATE phase SET last_job_state=? WHERE id=?
        phaseRepository.updateLastJobState(phaseId, stepADoneState);

        LOG.info(
                "OrchestratorStepAExecutor: DONE tournamentId={}, phaseId={} last_job_state='{}'"
                        + " (column-scoped UPDATE, E55S09 H-B defense-in-depth)",
                tournamentId,
                phaseId,
                stepADoneState);
    }
}
