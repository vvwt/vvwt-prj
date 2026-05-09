package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Leg 3 implementation: cancelable in-process slot optimization using cooperative cancellation via
 * a {@link CancellationToken} checked between lap permutations (E27S02, E51S11, DEC-49 D-3).
 *
 * <h2>Algorithm (E51S11 N-redefinition)</h2>
 *
 * <ol>
 *   <li>Map the phase to {@link MappingResult} via {@link PhaseToRawPhaseDefMapper}.
 *   <li>Compute {@code lapCount = rowCount / fieldCount}.
 *   <li>Iterate all lapCount! lap permutations using {@link LehmerCodec#rankToPermutation}, expand
 *       each to a row sequence ({@code rowSeq[i] = π[i/fc]*fc + i%fc}), and score via {@link
 *       VarietyScorer#scoreWithMatrix}. Check {@link CancellationToken#isCancelled()} between each
 *       permutation (cooperative cancellation).
 *   <li>Update the active {@link JobHandle}'s best-so-far after each improvement.
 *   <li>On cancellation or natural completion, apply the best result via {@link
 *       SlotResultApplicator#applyResult(long, int, MappingResult)} (DEC-49 D-11a).
 *   <li>On cancellation BEFORE any permutation evaluated: apply rank=0 (L2 baseline = identity lap
 *       permutation). This preserves L2's deterministic slot assignment (E51S11
 *       AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED).
 * </ol>
 *
 * <h2>L2 baseline on cancel-before-permutation (E51S11)</h2>
 *
 * <p>Previously, cancel-before-permutation applied "trivial coordinates" (lap 0, sequential fields)
 * directly. As of E51S11, rank=0 (identity lap permutation = L2 output unchanged) is used instead,
 * consistent with {@link SlotResultApplicator#applyResult}.
 *
 * <h2>Per DEC-35</h2>
 *
 * <p>Implementation in {@code de.vvwt.tm.slotopt.internal}; public interface {@link
 * CancelableInProcessSlotOptimizationService} in module root.
 *
 * @see CancelableInProcessSlotOptimizationService
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3,
 *     D-11a</a>
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="E51S11">E51S11 — lapCount loop + L2 baseline on cancel</a>
 */
@Service
public class DefaultCancelableInProcessSlotOptimizationService
        implements CancelableInProcessSlotOptimizationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultCancelableInProcessSlotOptimizationService.class);

    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotResultApplicator applicator;
    private final SlotOptimizationJobRegistry registry;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param mapper the phase-to-raw-phase-def mapper
     * @param applicator the result applicator (applies lap-permutation rank to match entities)
     * @param registry the job registry for best-so-far tracking
     */
    @Autowired
    public DefaultCancelableInProcessSlotOptimizationService(
            PhaseToRawPhaseDefMapper mapper,
            SlotResultApplicator applicator,
            SlotOptimizationJobRegistry registry) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.mapper = mapper;
        this.applicator = applicator;
        this.registry = registry;
    }

    /** {@inheritDoc} */
    @Override
    public OptimizationResult optimize(UUID phaseId, UUID tournamentId, CancellationToken token) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (tournamentId == null) {
            throw new IllegalArgumentException("tournamentId must not be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }

        MappingResult mapping = mapper.map(phaseId);
        CanonicalPhaseDef canonical = mapping.canonical();
        int rowCount = canonical.rowCount();
        int fieldCount = mapper.getFieldCount();
        int lapCount = rowCount / fieldCount;
        Optional<JobHandle> handleOpt = registry.getHandle(tournamentId);

        // Case: cancelled before any computation begins — apply rank=0 (L2 baseline)
        // AC-TEST-CANCELABLE-BEST-SO-FAR-ON-L2-DEFAULT-RED (E51S11)
        if (token.isCancelled()) {
            LOG.info(
                    "CancelableInProcessSlotOptimizationService: phase={} cancelled before"
                            + " computation; applying rank=0 (L2 baseline)",
                    phaseId);
            applicator.applyResult(0L, fieldCount, mapping);
            return OptimizationResult.cancelled(0L, Double.MAX_VALUE);
        }

        // Build scoring infrastructure
        VarietyScorer scorer = new VarietyScorer();
        boolean[][] activeMatrix =
                scorer.buildActiveMatrix(canonical.rows(), rowCount, canonical.avatarCount());

        long totalPermutations = factorial(lapCount);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        boolean wasCancelled = false;

        long startMs = System.currentTimeMillis();

        // Iterate over lapCount! lap permutations (E51S11 N-redefinition)
        for (long rank = 0L; rank < totalPermutations; rank++) {
            // Cooperative cancellation check
            if (token.isCancelled()) {
                wasCancelled = true;
                break;
            }

            // Expand lap permutation π to row sequence: rowSeq[i] = π[i/fc]*fc + i%fc
            int[] pi = LehmerCodec.rankToPermutation(rank, lapCount);
            int[] rowSeq = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                rowSeq[i] = pi[i / fieldCount] * fieldCount + i % fieldCount;
            }

            double score =
                    scorer.scoreWithMatrix(rowSeq, rowCount, canonical.avatarCount(), activeMatrix);

            // Strict improvement (lowest rank wins on tie)
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
                final long capturedRank = bestRank;
                final double capturedScore = bestScore;
                handleOpt.ifPresent(
                        h ->
                                h.updateBestSoFar(
                                        OptimizationResult.cancelled(capturedRank, capturedScore)));
            }
        }

        long wallClockMs = System.currentTimeMillis() - startMs;
        LOG.info(
                "CancelableInProcessSlotOptimizationService: phase={}, lapCount={}, fieldCount={},"
                        + " perms={}, wallClockMs={}, bestScore={}, cancelled={}",
                phaseId,
                lapCount,
                fieldCount,
                totalPermutations,
                wallClockMs,
                bestScore,
                wasCancelled);

        // Apply the best result (natural completion or best-so-far on cancel)
        applicator.applyResult(bestRank, fieldCount, mapping);

        return wasCancelled
                ? OptimizationResult.cancelled(bestRank, bestScore)
                : OptimizationResult.completed(bestRank, bestScore);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static long factorial(int n) {
        if (n < 1) {
            return 1L; // 0! = 1, handles empty/trivial phases gracefully
        }
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
