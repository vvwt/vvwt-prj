package de.vvwt.tm.slotopt.internal;

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
     * Executes Leg 1: delegates to {@link LapPermutationOptimizer} for the correct DEC-61-B +
     * DEC-63-C + E54S12 algorithm (E54S13 extract-and-delegate refactor).
     *
     * <p>All 4 bug-classes (stale lapCount, hardcoded VarietyScorer, canonical.rows() activeMatrix,
     * stale rowSeq expansion) are eliminated structurally by delegation.
     *
     * @param phaseId the phase being optimized (for logging)
     * @param phaseMapping the phase-global mapping result (DEC-61 Clause D, E54S03)
     * @param lapCount the number of laps in the phase (= canonical.rowCount() post-E54S02)
     * @param fieldCount the number of fields per lap
     */
    private void executeLeg1Inline(
            UUID phaseId, MappingResult phaseMapping, int lapCount, int fieldCount) {
        // Delegate to LapPermutationOptimizer — correct DEC-61-B + DEC-63-C + E54S12 algorithm.
        // All 4 bug-classes eliminated by delegation (E54S13).
        // No token (Leg 1 cancellation not needed); no job handle (Leg 1 has no best-so-far).
        LapPermutationOptimizer.optimize(
                phaseId,
                phaseMapping,
                fieldCount,
                scorerConfig,
                applicator,
                null,
                Optional.empty());
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
}
