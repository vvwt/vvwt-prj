package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-process exhaustive slot-optimization client (E04S03).
 *
 * <p>Implements {@link SlotOptimizationClient} using an exhaustive lap-permutation search (E51S11).
 * For phases with lapCount &le; {@code tm.slotopt.exhaustive-max-n} rows, it searches all
 * lapCount! lap permutations and applies the globally optimal result via {@link
 * SlotResultApplicator#applyResult}.
 *
 * <h2>N = lapCount (E51S11 N-redefinition, DEC-49 D-3)</h2>
 *
 * <p>As of E51S11, N = {@code lapCount = rowCount / fieldCount}. The threshold check, the search
 * space, and {@link SlotResultApplicator#applyResult} all use lapCount as the permutation
 * dimension. The row sequence for scoring is expanded from the lap permutation π via:
 * {@code rowSeq[i] = π[i/fc]*fc + i%fc}.
 *
 * <h2>Slot assignment</h2>
 *
 * <p>After finding the optimal lap permutation rank, {@link SlotResultApplicator#applyResult} is
 * called with the best rank. This writes {@code lapNumber} and {@code fieldNumber} to every match.
 *
 * <h2>DEC-4 V1 amendment</h2>
 *
 * <p>Per DEC-4 V1 amendment (2026-04-12), the Tournament Manager is permitted to call the
 * optimization in-process. The dispatcher HTTP integration is deferred to a future Epic.
 *
 * <h2>Bean wiring</h2>
 *
 * <p>This is a {@link Service} bean of type {@link SlotOptimizationClient}. As of E27S01, {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} is the {@code @Primary} bean. Since
 * E51S11, RoutingSlotOptimizationClient inlines its own Leg 1 loop and calls the applicator
 * directly. This class is retained for direct invocations (e.g., integration tests, legacy paths).
 *
 * <h2>Optimality guarantee (AC3)</h2>
 *
 * <p>For lapCount &le; {@code exhaustiveMaxN}, the result is the global optimum over all lapCount!
 * lap permutations.
 *
 * <h2>Tenant scoping (AC14)</h2>
 *
 * <p>All repository calls delegate to tenant-scoped repositories (DEC-5, E03S05). The TenantContext
 * must be active before calling {@link #optimize(UUID)}.
 *
 * @see SlotOptimizationClient
 * @see de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient
 * @see SlotResultApplicator
 * @see <a href="../../../../../../../../docs/governance/stories/E04S03.story.md">Story E04S03</a>
 * @see <a href="E51S11">E51S11 — N = lapCount N-redefinition</a>
 */
@Service
public class DirectSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DirectSlotOptimizationClient.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotResultApplicator applicator;
    private final int exhaustiveMaxN;

    /**
     * Constructs the client.
     *
     * @param phaseRepository tenant-scoped repository for Phase entities
     * @param matchRepository tenant-scoped repository for Match entities
     * @param mapper forward mapper for Phase → RawPhaseDef
     * @param applicator result applicator for applying the best rank to match entities (E51S11)
     * @param exhaustiveMaxN maximum lapCount for exhaustive search; configured via {@code
     *     tm.slotopt.exhaustive-max-n}
     */
    public DirectSlotOptimizationClient(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseToRawPhaseDefMapper mapper,
            SlotResultApplicator applicator,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN) {
        if (phaseRepository == null) {
            throw new IllegalArgumentException("phaseRepository must not be null");
        }
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
        this.applicator = applicator;
        this.exhaustiveMaxN = exhaustiveMaxN;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For lapCount &le; {@code tm.slotopt.exhaustive-max-n} (default: 10):
     *
     * <ol>
     *   <li>Maps the phase to {@link MappingResult} via the forward mapper.
     *   <li>Computes {@code lapCount = rowCount / fieldCount}.
     *   <li>Iterates all lapCount! lap permutations, expanding each to a row sequence for scoring.
     *   <li>Calls {@link SlotResultApplicator#applyResult(long, int, MappingResult)} with the best
     *       rank.
     * </ol>
     *
     * <p>For lapCount &lt; 2: applies identity rank (0) — L2 baseline preserved.
     *
     * <p>For lapCount &gt; {@code exhaustiveMaxN}: throws {@link UnsupportedOperationException}
     * until Leg 2/3 handles the phase.
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist
     * @throws IllegalStateException if no matches exist for the phase
     * @throws UnsupportedOperationException if lapCount &gt; {@code exhaustiveMaxN}
     */
    @Override
    public void optimize(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // Verify phase exists
        phaseRepository
                .findById(phaseId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "DirectSlotOptimizationClient: phase not found: "
                                                + phaseId));

        // Verify matches exist early for a clearer error
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "DirectSlotOptimizationClient: no matches found for phase "
                            + phaseId
                            + ". Cannot optimize empty phase.");
        }

        // Forward-map to RawPhaseDef + canonical form
        MappingResult mapping = mapper.map(phaseId);
        CanonicalPhaseDef canonical = mapping.canonical();
        int rowCount = canonical.rowCount();
        int fieldCount = mapper.getFieldCount();

        // N = lapCount (E51S11 N-redefinition, DEC-49 D-3)
        int lapCount = rowCount / fieldCount;

        // Trivial phase (fewer than 2 laps) — apply identity rank (L2 baseline)
        if (lapCount < 2) {
            LOG.warn(
                    "DirectSlotOptimizationClient: phase={}, lapCount={} (< 2) — trivial phase,"
                            + " applying rank=0 (L2 baseline)",
                    phaseId,
                    lapCount);
            applicator.applyResult(0L, fieldCount, mapping);
            return;
        }

        // lapCount > exhaustiveMaxN — not handled here; routing client should have routed to Leg 2/3
        if (lapCount > exhaustiveMaxN) {
            throw new UnsupportedOperationException(
                    "Exhaustive optimization not feasible for lapCount="
                            + lapCount
                            + " (> "
                            + exhaustiveMaxN
                            + "). Route to Leg 2/3.");
        }

        // Build scoring infrastructure
        VarietyScorer scorer = new VarietyScorer();
        boolean[][] activeMatrix =
                scorer.buildActiveMatrix(canonical.rows(), rowCount, canonical.avatarCount());

        long totalPermutations = factorial(lapCount);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        long startMs = System.currentTimeMillis();

        // Exhaustive lap-permutation loop (E51S11 Option-3 flat-index)
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

        long wallClockMs = System.currentTimeMillis() - startMs;
        LOG.info(
                "DirectSlotOptimizationClient: phase={}, lapCount={}, fieldCount={}, perms={},"
                        + " wallClockMs={}, bestRank={}, bestScore={}",
                phaseId,
                lapCount,
                fieldCount,
                totalPermutations,
                wallClockMs,
                bestRank,
                bestScore);

        // Apply the best lap-permutation rank via SlotResultApplicator (AC-IMPL-OPTION-3-FLAT-INDEX)
        applicator.applyResult(bestRank, fieldCount, mapping);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes N! for N in [0, 20]. All values fit in {@code long}.
     *
     * @param n the value whose factorial to compute
     * @return n!
     * @throws IllegalArgumentException if n &lt; 0
     */
    static long factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, got: " + n);
        }
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
