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
import de.vvwt.tm.tournament.MatchRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Leg 3 implementation: cancelable in-process slot optimization using cooperative cancellation via
 * a {@link CancellationToken} checked between permutations (E27S02, AC-CANCELABLE-SERVICE-AUTHORED,
 * DEC-49 D-3).
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Map the phase to {@link MappingResult} via {@link PhaseToRawPhaseDefMapper}.
 *   <li>Iterate all N! permutations using {@link LehmerCodec#rankToPermutation} + {@link
 *       VarietyScorer#scoreWithMatrix}, checking {@link CancellationToken#isCancelled()} between
 *       each permutation (cooperative cancellation per AC-COOPERATIVE-CANCELLATION-TESTED).
 *   <li>After each permutation, update the active {@link JobHandle}'s best-so-far via {@link
 *       JobHandle#updateBestSoFar} (AC-BEST-SO-FAR-NON-NULL-AFTER-FIRST-PERMUTATION).
 *   <li>On cancellation or natural completion, apply the best result via {@link
 *       SlotResultApplicator#applyResult} (DEC-49 D-11a).
 *   <li>On cancellation BEFORE any permutation evaluated: apply trivial coordinates (lap 0,
 *       sequential fields) via {@link MatchRepository} directly — analogous to {@link
 *       de.vvwt.tm.slotopt.FallbackSlotOptimizationClient}.
 * </ol>
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
 */
@Service
public class DefaultCancelableInProcessSlotOptimizationService
        implements CancelableInProcessSlotOptimizationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultCancelableInProcessSlotOptimizationService.class);

    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotResultApplicator applicator;
    private final SlotOptimizationJobRegistry registry;
    private final MatchRepository matchRepository;

    @Value("${tm.slotopt.fallback.field-count:3}")
    private int fieldCount;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param mapper the phase-to-raw-phase-def mapper
     * @param applicator the result applicator (applies permutation rank to match entities)
     * @param registry the job registry for best-so-far tracking
     * @param matchRepository the tenant-scoped match repository (for trivial-coordinate fallback)
     */
    @Autowired
    public DefaultCancelableInProcessSlotOptimizationService(
            PhaseToRawPhaseDefMapper mapper,
            SlotResultApplicator applicator,
            SlotOptimizationJobRegistry registry,
            MatchRepository matchRepository) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        this.mapper = mapper;
        this.applicator = applicator;
        this.registry = registry;
        this.matchRepository = matchRepository;
    }

    /**
     * Test-only constructor without MatchRepository (uses mocks for tests that don't exercise
     * trivial path).
     */
    DefaultCancelableInProcessSlotOptimizationService(
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
        this.matchRepository = null;
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
        int n = canonical.rowCount();
        Optional<JobHandle> handleOpt = registry.getHandle(tournamentId);

        // Case: cancelled before any computation begins
        if (token.isCancelled()) {
            LOG.info(
                    "CancelableInProcessSlotOptimizationService: phase={} cancelled before"
                            + " computation; applying trivial coordinates",
                    phaseId);
            // Apply rank 0 (trivial: sequential as-inserted order) as best-so-far
            applicator.applyResult(0L, fieldCount, mapping);
            return OptimizationResult.cancelled(0L, Double.MAX_VALUE);
        }

        // Build scoring infrastructure (same pattern as PacketSolver)
        VarietyScorer scorer = new VarietyScorer();
        boolean[][] activeMatrix =
                scorer.buildActiveMatrix(
                        canonical.rows(), canonical.rowCount(), canonical.avatarCount());

        long totalPermutations = factorial(n);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        boolean wasCancelled = false;

        long startMs = System.currentTimeMillis();

        for (long rank = 0L; rank < totalPermutations; rank++) {
            // Cooperative cancellation check (AC-COOPERATIVE-CANCELLATION-TESTED)
            if (token.isCancelled()) {
                wasCancelled = true;
                break;
            }

            int[] permutation = LehmerCodec.rankToPermutation(rank, n);
            double score =
                    scorer.scoreWithMatrix(
                            permutation,
                            canonical.rowCount(),
                            canonical.avatarCount(),
                            activeMatrix);

            // Strict improvement (same tie-break as PacketSolver: lowest rank wins)
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
                // Capture final copies for lambda (bestRank/bestScore are mutated variables)
                final long capturedRank = bestRank;
                final double capturedScore = bestScore;
                // Update best-so-far on the job handle for status queries
                handleOpt.ifPresent(
                        h ->
                                h.updateBestSoFar(
                                        OptimizationResult.cancelled(capturedRank, capturedScore)));
            }
        }

        long wallClockMs = System.currentTimeMillis() - startMs;
        LOG.info(
                "CancelableInProcessSlotOptimizationService: phase={}, N={}, perms={}, "
                        + "wallClockMs={}, bestScore={}, cancelled={}",
                phaseId,
                n,
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
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
