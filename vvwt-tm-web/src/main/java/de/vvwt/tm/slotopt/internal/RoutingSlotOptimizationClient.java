package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawRow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <h2>Three-leg routing (DEC-49 D-3, E51S11 N-redefinition)</h2>
 *
 * <p>As of E51S11, N = {@code lapCount = rowCount / fieldCount} per group (not rowCount). The
 * routing decision is made per-group:
 *
 * <ul>
 *   <li><strong>Leg 1 (E27S01):</strong> lapCount &le; {@code tm.slotopt.exhaustive-max-n} (default
 *       10) → exhaustive in-process lap-permutation search; {@link
 *       SlotResultApplicator#applyResult} called per group.
 *   <li><strong>Leg 2 (E27S03):</strong> lapCount &gt; threshold AND dispatcher reachable → HTTP
 *       submit to vvwt-slotopt-dispatcher. On any wire error, falls through to Leg 3.
 *   <li><strong>Leg 3 (E27S02):</strong> lapCount &gt; threshold AND dispatcher NOT reachable (or
 *       Leg 2 fails) → register job + call {@link CancelableInProcessSlotOptimizationService}.
 * </ul>
 *
 * <h2>Per-group L3 iteration (NF-MED-1, E51S11)</h2>
 *
 * <p>{@link #optimize(UUID)} iterates over distinct group numbers in the phase mapping (derived
 * from {@link PositionTuple#group()} of each raw row). For each group, a per-group {@link
 * MappingResult} is obtained via {@link PhaseToRawPhaseDefMapper#mapGroup(UUID, int)} and routed
 * independently. Leg 1 applies results via {@link SlotResultApplicator} per group inline. Legs 2/3
 * delegate to the phase-level cancelable service.
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
 * @see <a href="E51S11">E51S11 — lapCount N-redefinition + per-group iteration</a>
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
     * Constructs the routing client with all dependencies for Leg 1, Leg 2, and Leg 3 (E27S01,
     * E27S03, E27S02) plus per-group lap-permutation optimization (E51S11).
     *
     * @param directClient the exhaustive in-process client (backward compat; not used for Leg 1
     *     since E51S11 — lap-permutation loop is inlined)
     * @param cancelableService the Leg 3 cancelable in-process service
     * @param jobRegistry the per-tournament job handle registry
     * @param mapper the phase-to-raw-phase-def mapper (used to derive groups and per-group
     *     lapCount)
     * @param reachabilityService checks if the dispatcher is reachable before attempting Leg 2
     * @param dispatcherClient the HTTP client for Leg 2 dispatcher submission (E27S03)
     * @param applicator the result applicator for Leg 1 per-group result application (E51S11)
     * @param exhaustiveMaxN maximum lapCount for Leg 1; sourced from {@code
     *     tm.slotopt.exhaustive-max-n} (default 10) per DEC-49 D-3
     */
    public RoutingSlotOptimizationClient(
            DirectSlotOptimizationClient directClient,
            CancelableInProcessSlotOptimizationService cancelableService,
            SlotOptimizationJobRegistry jobRegistry,
            PhaseToRawPhaseDefMapper mapper,
            DispatcherReachabilityService reachabilityService,
            SlotOptimizationDispatcherClient dispatcherClient,
            SlotResultApplicator applicator,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN) {
        this.directClient = directClient;
        this.cancelableService = cancelableService;
        this.jobRegistry = jobRegistry;
        this.mapper = mapper;
        this.reachabilityService = reachabilityService;
        this.dispatcherClient = dispatcherClient;
        this.applicator = applicator;
        this.exhaustiveMaxN = exhaustiveMaxN;
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Routing (DEC-49 D-3, E51S11):</strong>
     *
     * <ol>
     *   <li>Map the phase via {@link PhaseToRawPhaseDefMapper#map(UUID)} to get the full mapping.
     *   <li>Extract distinct group numbers from the raw mapping's {@link PositionTuple#group()}
     *       values.
     *   <li>For each group: get per-group {@link MappingResult} via {@link
     *       PhaseToRawPhaseDefMapper#mapGroup(UUID, int)}.
     *   <li>Compute lapCount = {@code groupMapping.canonical().rowCount() / fieldCount}.
     *   <li>Route per lapCount:
     *       <ul>
     *         <li>lapCount &le; threshold → Leg 1 inline exhaustive lap-permutation; apply via
     *             {@link SlotResultApplicator#applyResult}.
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
        // Map the full phase to get group structure
        MappingResult fullMapping = mapper.map(phaseId);
        int fieldCount = mapper.getFieldCount();

        // Extract distinct group numbers from the raw mapping rows (PositionTuple.group())
        Set<Integer> groupNumbers = extractGroupNumbers(fullMapping);

        LOG.info(
                "RoutingSlotOptimizationClient: phase={}, groups={}, fieldCount={}",
                phaseId,
                groupNumbers,
                fieldCount);

        // Derive tournamentId for Leg 2/3 registry from first match in full mapping
        List<Match> allMatches = fullMapping.matchOrder();
        UUID tournamentId = allMatches.isEmpty() ? null : allMatches.get(0).getTournamentId();

        // Per-group routing (NF-MED-1, E51S11)
        for (int groupNumber : groupNumbers) {
            MappingResult groupMapping = mapper.mapGroup(phaseId, groupNumber);
            int groupRowCount = groupMapping.canonical().rowCount();
            int lapCount = groupRowCount / fieldCount;

            LOG.debug(
                    "RoutingSlotOptimizationClient: phase={} group={}, rowCount={}, lapCount={},"
                            + " threshold={}",
                    phaseId,
                    groupNumber,
                    groupRowCount,
                    lapCount,
                    exhaustiveMaxN);

            if (lapCount <= exhaustiveMaxN) {
                // Leg 1: exhaustive in-process lap-permutation (lapCount ≤ threshold)
                LOG.debug(
                        "RoutingSlotOptimizationClient: phase={} group={}, lapCount={} ≤"
                                + " threshold={} → Leg 1 inline",
                        phaseId,
                        groupNumber,
                        lapCount,
                        exhaustiveMaxN);
                executeLeg1Inline(phaseId, groupNumber, groupMapping, lapCount, fieldCount);
            } else {
                // lapCount > threshold: Leg 2 or Leg 3 (per-phase, not per-group, for Leg 2/3)
                // Phase-level Legs 2/3 are invoked once for the first above-threshold group;
                // subsequent above-threshold groups in the same phase are covered by the
                // phase-level cancelable service.
                if (reachabilityService.isReachable()) {
                    LOG.info(
                            "RoutingSlotOptimizationClient: phase={} group={}, lapCount={} >"
                                    + " threshold={}, dispatcher reachable → attempting Leg 2",
                            phaseId,
                            groupNumber,
                            lapCount,
                            exhaustiveMaxN);
                    boolean leg2Succeeded = tryLeg2(phaseId, groupMapping);
                    if (leg2Succeeded) {
                        continue;
                    }
                    LOG.info(
                            "RoutingSlotOptimizationClient: Leg 2 failed for phase={} group={} →"
                                    + " falling through to Leg 3",
                            phaseId,
                            groupNumber);
                } else {
                    LOG.info(
                            "RoutingSlotOptimizationClient: phase={} group={}, lapCount={} >"
                                    + " threshold={}, dispatcher NOT reachable → Leg 3",
                            phaseId,
                            groupNumber,
                            lapCount,
                            exhaustiveMaxN);
                }
                // Leg 3: cancelable in-process (phase-level)
                executeLeg3(phaseId, tournamentId);
                // Once Leg 3 runs for the phase, stop iterating groups (Leg 3 is phase-level)
                return;
            }
        }
    }

    /**
     * Extracts distinct group numbers from the raw mapping's rows, in encounter order.
     *
     * <p>Each {@link RawRow} contains {@link PositionTuple}s whose {@link PositionTuple#group()}
     * corresponds to {@link de.vvwt.tm.tournament.TeamAvatar#getGroupNumber()}.
     *
     * @param mapping the full phase mapping
     * @return distinct group numbers in encounter order (LinkedHashSet)
     */
    private static Set<Integer> extractGroupNumbers(MappingResult mapping) {
        Set<Integer> groups = new LinkedHashSet<>();
        for (RawRow row : mapping.raw().rows()) {
            for (PositionTuple pt : row.positions()) {
                groups.add(pt.group());
            }
        }
        return groups;
    }

    /**
     * Executes Leg 1 inline: exhaustive lap-permutation search over {@code [0, lapCount!)} ranks.
     *
     * <p>For each rank, the lap permutation is expanded to a row sequence and scored via {@link
     * VarietyScorer}. The best rank is applied via {@link SlotResultApplicator#applyResult}.
     *
     * <p>For lapCount &lt; 2: applies identity rank (0) — L2 baseline preserved (AC9).
     *
     * @param phaseId the phase being optimized (for logging)
     * @param groupNumber the group number (for logging)
     * @param groupMapping the per-group mapping result
     * @param lapCount the number of laps in this group
     * @param fieldCount the number of fields per lap
     */
    private void executeLeg1Inline(
            UUID phaseId,
            int groupNumber,
            MappingResult groupMapping,
            int lapCount,
            int fieldCount) {
        if (lapCount < 2) {
            // Trivial phase: apply identity (L2 baseline)
            LOG.debug(
                    "RoutingSlotOptimizationClient.executeLeg1Inline: phase={} group={},"
                            + " lapCount={} < 2 → identity rank=0 (L2 baseline)",
                    phaseId,
                    groupNumber,
                    lapCount);
            applicator.applyResult(0L, fieldCount, groupMapping);
            return;
        }

        CanonicalPhaseDef canonical = groupMapping.canonical();
        int rowCount = canonical.rowCount();

        VarietyScorer scorer = new VarietyScorer();
        boolean[][] activeMatrix =
                scorer.buildActiveMatrix(canonical.rows(), rowCount, canonical.avatarCount());

        long totalPermutations = factorial(lapCount);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;

        for (long rank = 0L; rank < totalPermutations; rank++) {
            // Expand lap permutation π to row sequence: rowSeq[i] = π[i/fc]*fc + i%fc
            int[] pi = LehmerCodec.rankToPermutation(rank, lapCount);
            int[] rowSeq = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                rowSeq[i] = pi[i / fieldCount] * fieldCount + i % fieldCount;
            }

            double score =
                    scorer.scoreWithMatrix(rowSeq, rowCount, canonical.avatarCount(), activeMatrix);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }

        LOG.info(
                "RoutingSlotOptimizationClient.executeLeg1Inline: phase={} group={}, lapCount={},"
                        + " perms={}, bestRank={}, bestScore={}",
                phaseId,
                groupNumber,
                lapCount,
                totalPermutations,
                bestRank,
                bestScore);

        applicator.applyResult(bestRank, fieldCount, groupMapping);
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

    /** Computes n! for n in [0, 20]. Fits in {@code long}. */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
