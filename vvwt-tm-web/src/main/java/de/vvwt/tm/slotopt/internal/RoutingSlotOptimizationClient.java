package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.Scorer;
import de.vvwt.slotopt.worker.score.ScorerFactory;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.DispatcherAlgorithmMismatchException;
import de.vvwt.tm.slotopt.DispatcherReachabilityService;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationDispatcherClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import de.vvwt.tm.tournament.Match;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Canonical {@link SlotOptimizationClient} entry point for Tournament Manager business code (E27S01
 * + E27S02 + E27S03 — Leg 1, Leg 3, and Leg 2 routing).
 *
 * <p>This class is the {@code @Primary @Service} bean registered as the project-wide canonical
 * {@link SlotOptimizationClient} implementation from E27S01 forward.
 *
 * <h2>Three-leg routing — phase-global (DEC-49 D-3, DEC-61 Clause D)</h2>
 *
 * <p>As of E54S03 (DEC-61 Clause D), the routing decision is phase-global: a single {@link
 * PhaseToRawPhaseDefMapper#map(UUID)} call produces the full phase mapping. N = {@code lapCount =
 * canonical.rowCount()} (post-E54S02 Mapper-refactor, each RawRow is a lap-row). All three legs
 * operate on the phase-global mapping:
 *
 * <ul>
 *   <li><strong>Leg 1 (E27S01):</strong> lapCount &le; {@code tm.slotopt.exhaustive-max-n} (default
 *       10) → exhaustive in-process lap-permutation search; {@link
 *       SlotResultApplicator#applyResult} called once with the phase-global mapping.
 *   <li><strong>Leg 2 (E27S03):</strong> lapCount &gt; threshold AND dispatcher reachable → HTTP
 *       submit to vvwt-slotopt-dispatcher. On any wire error, falls through to Leg 3.
 *   <li><strong>Leg 3 (E27S02):</strong> lapCount &gt; threshold AND dispatcher NOT reachable (or
 *       Leg 2 fails) → register job + call {@link CancelableInProcessSlotOptimizationService}.
 * </ul>
 *
 * <h2>DEC-61 Clause D — elimination of per-group lap-offset collapse (E54S03)</h2>
 *
 * <p>Prior to E54S03, {@link #optimize(UUID)} iterated over distinct group numbers, obtaining
 * per-group mappings via {@code mapGroup(phaseId, groupNumber)}. {@link
 * SlotResultApplicator#applyResult} rewrote {@code lapNumber = outputLapIndex + 1} per per-group
 * call — discarding Group 2's L2-assigned offsets (Defect 3 in DEC-61). E54S03 eliminates this by
 * passing the phase-global mapping to a single {@code applyResult} call.
 *
 * <h2>Bean wiring</h2>
 *
 * <p>{@code @Primary} makes this bean the preferred {@link SlotOptimizationClient} for any
 * {@code @Autowired} injection point. {@link DirectSlotOptimizationClient} is retained for backward
 * compatibility but no longer invoked directly by this class for Leg 1 (lap-permutation loop is
 * inlined here).
 *
 * @see SlotOptimizationClient
 * @see DirectSlotOptimizationClient
 * @see CancelableInProcessSlotOptimizationService
 * @see SlotOptimizationJobRegistry
 * @see SlotOptimizationDispatcherClient
 * @see DispatcherReachabilityService
 * @see SlotResultApplicator
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-61.md">DEC-61 Clause D —
 *     L3 phase-global invocation</a>
 * @see <a href="E54S03">E54S03 — L3 phase-global invocation + asymmetric regression-IT</a>
 */
@Primary
@Service
public class RoutingSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingSlotOptimizationClient.class);

    private final DirectSlotOptimizationClient directClient;
    private final CancelableInProcessSlotOptimizationService cancelableService;
    private final SlotOptimizationJobRegistry jobRegistry;
    private final PhaseToRawPhaseDefMapper mapper;
    private final DispatcherReachabilityService reachabilityService;
    private final SlotOptimizationDispatcherClient dispatcherClient;
    private final SlotResultApplicator applicator;
    private final int exhaustiveMaxN;

    /**
     * Scorer configuration value sourced from {@code tm.slotopt.scorer} (DEC-63 Clause C).
     *
     * <p>Valid values: {@code "mean"} (default, VarietyScorer) or {@code "balanced"}
     * (BalancedVarietyScorer). Invalid values fall back to {@code "mean"} with a WARN log.
     *
     * @see ScorerFactory#createScorerUnified(String)
     */
    private final String scorerConfig;

    /**
     * Constructs the routing client with all dependencies for Leg 1, Leg 2, and Leg 3 (E27S01,
     * E27S03, E27S02) plus phase-global lap-permutation optimization (E54S03 / DEC-61 Clause D).
     *
     * @param directClient the exhaustive in-process client (backward compat; not used for Leg 1
     *     since E51S11 — lap-permutation loop is inlined)
     * @param cancelableService the Leg 3 cancelable in-process service
     * @param jobRegistry the per-tournament job handle registry
     * @param mapper the phase-to-raw-phase-def mapper (phase-global {@code map(phaseId)} only
     *     post-E54S03; {@code mapGroup} deleted as dead code per DEC-61 Clause D)
     * @param reachabilityService checks if the dispatcher is reachable before attempting Leg 2
     * @param dispatcherClient the HTTP client for Leg 2 dispatcher submission (E27S03)
     * @param applicator the result applicator; invoked once with the phase-global mapping (E54S03)
     * @param exhaustiveMaxN maximum lapCount for Leg 1; sourced from {@code
     *     tm.slotopt.exhaustive-max-n} (default 10) per DEC-49 D-3
     * @param scorerConfig scorer selection; sourced from {@code tm.slotopt.scorer} (default {@code
     *     "mean"}); valid values: {@code "mean"} (VarietyScorer) or {@code "balanced"}
     *     (BalancedVarietyScorer); invalid values fall back to {@code "mean"} with WARN log
     */
    public RoutingSlotOptimizationClient(
            DirectSlotOptimizationClient directClient,
            CancelableInProcessSlotOptimizationService cancelableService,
            SlotOptimizationJobRegistry jobRegistry,
            PhaseToRawPhaseDefMapper mapper,
            DispatcherReachabilityService reachabilityService,
            SlotOptimizationDispatcherClient dispatcherClient,
            SlotResultApplicator applicator,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN,
            @Value("${tm.slotopt.scorer:mean}") String scorerConfig) {
        this.directClient = directClient;
        this.cancelableService = cancelableService;
        this.jobRegistry = jobRegistry;
        this.mapper = mapper;
        this.reachabilityService = reachabilityService;
        this.dispatcherClient = dispatcherClient;
        this.applicator = applicator;
        this.exhaustiveMaxN = exhaustiveMaxN;
        this.scorerConfig = scorerConfig;
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Routing — phase-global (DEC-49 D-3, DEC-61 Clause D, E54S03):</strong>
     *
     * <ol>
     *   <li>Map the phase via {@link PhaseToRawPhaseDefMapper#map(UUID)} — single call
     *       (phase-global).
     *   <li>Derive {@code lapCount = phaseMapping.canonical().rowCount()} (post-E54S02: each RawRow
     *       is a lap-row, so {@code canonical.rowCount() = lapCount} directly per DEC-61 Clause B).
     *   <li>Empty phase (lapCount = 0) → no-op, return.
     *   <li>Route on lapCount:
     *       <ul>
     *         <li>lapCount &le; threshold → Leg 1 inline exhaustive lap-permutation; apply via
     *             {@link SlotResultApplicator#applyResult} with the phase-global mapping.
     *         <li>lapCount &gt; threshold + dispatcher reachable → Leg 2 (HTTP). On wire error →
     *             Leg 3.
     *         <li>lapCount &gt; threshold + dispatcher unreachable → Leg 3 (cancelable in-process).
     *       </ul>
     * </ol>
     *
     * @param phaseId the phase whose matches should receive slot assignments
     */
    @Override
    public void optimize(UUID phaseId) {
        // DEC-61 Clause D: phase-global mapping — single map() call (E54S03)
        MappingResult phaseMapping = mapper.map(phaseId);
        int fieldCount = mapper.getFieldCount();

        // DEC-61 Clause B: post-E54S02 canonical.rowCount() = lapCount directly.
        int lapCount = phaseMapping.canonical().rowCount();

        LOG.info(
                "RoutingSlotOptimizationClient: phase={}, lapCount={}, fieldCount={}",
                phaseId,
                lapCount,
                fieldCount);

        // Empty-phase guard: no matches → no-op (AC-ERROR-EMPTY-PHASE-NO-OP)
        if (lapCount == 0) {
            LOG.debug("RoutingSlotOptimizationClient: phase={} has lapCount=0 → no-op", phaseId);
            return;
        }

        // Derive tournamentId for Leg 2/3 registry from first match in phase mapping
        List<Match> allMatches = phaseMapping.matchOrder();
        UUID tournamentId = allMatches.isEmpty() ? null : allMatches.get(0).getTournamentId();

        // Phase-global routing (DEC-61 Clause D, E54S03)
        if (lapCount <= exhaustiveMaxN) {
            // Leg 1: exhaustive in-process lap-permutation (lapCount ≤ threshold)
            LOG.debug(
                    "RoutingSlotOptimizationClient: phase={}, lapCount={} ≤ threshold={}"
                            + " → Leg 1 inline (phase-global)",
                    phaseId,
                    lapCount,
                    exhaustiveMaxN);
            executeLeg1Inline(phaseId, phaseMapping, lapCount, fieldCount);
        } else if (reachabilityService.isReachable()) {
            LOG.info(
                    "RoutingSlotOptimizationClient: phase={}, lapCount={} > threshold={},"
                            + " dispatcher reachable → attempting Leg 2 (phase-global)",
                    phaseId,
                    lapCount,
                    exhaustiveMaxN);
            boolean leg2Succeeded = tryLeg2(phaseId, phaseMapping);
            if (!leg2Succeeded) {
                LOG.info(
                        "RoutingSlotOptimizationClient: Leg 2 failed for phase={} →"
                                + " falling through to Leg 3",
                        phaseId);
                executeLeg3(phaseId, tournamentId);
            }
        } else {
            LOG.info(
                    "RoutingSlotOptimizationClient: phase={}, lapCount={} > threshold={},"
                            + " dispatcher NOT reachable → Leg 3 (phase-global)",
                    phaseId,
                    lapCount,
                    exhaustiveMaxN);
            executeLeg3(phaseId, tournamentId);
        }
    }

    /**
     * Executes Leg 1 inline: exhaustive lap-permutation search over {@code [0, lapCount!)} ranks.
     *
     * <p>For each rank, the lap permutation is expanded to a row sequence and scored via the
     * configured {@link Scorer} (VarietyScorer or BalancedVarietyScorer per {@code
     * tm.slotopt.scorer}). The best rank is applied via {@link SlotResultApplicator#applyResult}.
     *
     * <p>For lapCount &lt; 2: applies identity rank (0) — L2 baseline preserved (AC9).
     *
     * @param phaseId the phase being optimized (for logging)
     * @param phaseMapping the phase-global mapping result (DEC-61 Clause D, E54S03)
     * @param lapCount the number of laps in the phase (= canonical.rowCount() post-E54S02)
     * @param fieldCount the number of fields per lap
     */
    private void executeLeg1Inline(
            UUID phaseId, MappingResult phaseMapping, int lapCount, int fieldCount) {
        if (lapCount < 2) {
            // Trivial phase: apply identity (L2 baseline)
            LOG.debug(
                    "RoutingSlotOptimizationClient.executeLeg1Inline: phase={},"
                            + " lapCount={} < 2 → identity rank=0 (L2 baseline)",
                    phaseId,
                    lapCount);
            applicator.applyResult(0L, fieldCount, phaseMapping);
            return;
        }

        // DEC-61 Clause B: post-E54S02 rows are lap-rows, rowCount = lapCount.
        // π IS the row sequence directly — no expansion needed.
        CanonicalPhaseDef canonical = phaseMapping.canonical();
        int rowCount = canonical.rowCount(); // = lapCount post-E54S02
        int avatarCount = canonical.avatarCount();

        // DEC-63 Clause C: scorer selected via tm.slotopt.scorer config property.
        // "mean" (default) → VarietyScorer adapter; "balanced" → BalancedVarietyScorer.
        // Invalid values fall back to "mean" with WARN log (see ScorerFactory).
        Scorer scorer = ScorerFactory.createScorerUnified(scorerConfig);

        // E54S12 FIX: build activeMatrix from original lap-row order (denseIdsByRawRow),
        // NOT from canonical.rows() (which is lex-sorted for fingerprinting,
        // row-order-independent).
        //
        // Root cause: canonical.rows() is lex-sorted (StructuralFingerprint.canonicalize() Step 3).
        // activeMatrix[i] = avatar-state for canonical row i (sorted position). But the permutation
        // π from LehmerCodec.rankToPermutation(rank, lapCount) indexes into original lap order
        // (0 = lap 1, 1 = lap 2, ...), and SlotResultApplicator.applyResult applies it to lapGroups
        // built in original ascending lap-number order. Using canonical.rows() causes an
        // index-space mismatch: the scorer evaluates permutations in canonical-row-index space, but
        // the applicator applies them in original-lap-number-index space. The optimal rank in
        // canonical space is NOT the optimal rank in original-lap space — producing sub-optimal
        // output for setups where the canonical row order differs from the original lap order
        // (e.g., 12T/2G/3F with pure-group laps: canonical = [G1×5, G2×5] vs original = alternating
        // G1/G2).
        //
        // Fix: use denseIdsByRawRow (preserves original lap-row order from
        // PhaseToRawPhaseDefMapper)
        // so scorer and applicator operate in the same index space.
        // DEC-63 Clause A preserved: VarietyScorer.java is textually unchanged; only the
        // row-ordering source for activeMatrix construction changes here. (E54S12)
        boolean[][] activeMatrix =
                buildActiveMatrixFromRawRows(
                        phaseMapping.denseIdsByRawRow(), rowCount, avatarCount);

        long totalPermutations = factorial(lapCount);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;

        for (long rank = 0L; rank < totalPermutations; rank++) {
            // Post-E54S02: π directly indexes lap-rows (rowCount = lapCount); no expansion.
            int[] rowSeq = LehmerCodec.rankToPermutation(rank, lapCount);

            double score = scorer.scoreWithMatrix(rowSeq, rowCount, avatarCount, activeMatrix);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }

        LOG.info(
                "RoutingSlotOptimizationClient.executeLeg1Inline: phase={}, lapCount={},"
                        + " perms={}, bestRank={}, bestScore={}",
                phaseId,
                lapCount,
                totalPermutations,
                bestRank,
                bestScore);

        applicator.applyResult(bestRank, fieldCount, phaseMapping);
    }

    /**
     * Attempts Leg 2: submits the job to the dispatcher and polls for result.
     *
     * <p>Per AC-LEG-2-FALLS-THROUGH-ON-WIRE-ERROR: on any error, returns {@code false} so the
     * caller falls through to Leg 3.
     *
     * @param phaseId the phase being optimized
     * @param groupMapping the mapping result for submission
     * @return {@code true} if Leg 2 succeeded; {@code false} to fall through to Leg 3
     */
    private boolean tryLeg2(UUID phaseId, MappingResult groupMapping) {
        try {
            UUID jobId = dispatcherClient.submitJob(groupMapping.raw());
            Optional<int[]> result = dispatcherClient.pollResult(jobId);
            if (result.isEmpty()) {
                LOG.warn(
                        "RoutingSlotOptimizationClient.tryLeg2: poll timeout for phase={}, job={}",
                        phaseId,
                        jobId);
                return false;
            }
            LOG.info(
                    "RoutingSlotOptimizationClient.tryLeg2: success for phase={}, job={}",
                    phaseId,
                    jobId);
            return true;
        } catch (DispatcherAlgorithmMismatchException e) {
            LOG.warn(
                    "RoutingSlotOptimizationClient.tryLeg2: algorithm mismatch for phase={},"
                            + " algorithmId={}, httpStatus={} → falling through to Leg 3",
                    phaseId,
                    e.getAlgorithmId(),
                    e.getHttpStatus());
            return false;
        } catch (RuntimeException e) {
            LOG.warn(
                    "RoutingSlotOptimizationClient.tryLeg2: wire error for phase={}: {} → falling"
                            + " through to Leg 3",
                    phaseId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Executes Leg 3: cancelable in-process optimization with job handle registration.
     *
     * <p>The registry is always completed (in {@code finally}) to prevent handle leaks, even if the
     * optimization throws.
     *
     * @param phaseId the phase being optimized
     * @param tournamentId the tournament owning the phase
     */
    private void executeLeg3(UUID phaseId, UUID tournamentId) {
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());
        jobRegistry.register(tournamentId, handle);
        try {
            cancelableService.optimize(phaseId, tournamentId, token);
        } finally {
            jobRegistry.complete(tournamentId);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the active-matrix from original lap-row order ({@code denseIdsByRawRow}).
     *
     * <p>This is the correct index space for the permutation-search: {@code activeMatrix[lapIdx]}
     * corresponds to original lap {@code lapIdx}, matching the lap-bucket order used by {@link
     * de.vvwt.tm.slotopt.SlotResultApplicator#applyResult} and the rank encoding from {@link
     * de.vvwt.slotopt.worker.codec.LehmerCodec#rankToPermutation}.
     *
     * <p>Do NOT use {@link de.vvwt.slotopt.worker.types.CanonicalPhaseDef#rows()} here — that list
     * is lex-sorted (row-order-independent for fingerprinting) and causes an index-space mismatch
     * with the applicator's original-lap-number-index space. (E54S12 root-cause fix)
     *
     * @param denseIdsByRawRow {@code [lapIdx][k]} = dense avatar ID k active in lap {@code lapIdx};
     *     from {@link de.vvwt.tm.slotopt.MappingResult#denseIdsByRawRow()}
     * @param rowCount number of laps
     * @param avatarCount total avatar count
     * @return active-matrix[lapIndex][avatarId] == true iff avatar is active in that lap
     */
    private static boolean[][] buildActiveMatrixFromRawRows(
            int[][] denseIdsByRawRow, int rowCount, int avatarCount) {
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int lapIndex = 0; lapIndex < rowCount; lapIndex++) {
            for (int avatarId : denseIdsByRawRow[lapIndex]) {
                activeMatrix[lapIndex][avatarId] = true;
            }
        }
        return activeMatrix;
    }

    /** Computes n! for n in [0, 20]. Fits in {@code long}. */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
