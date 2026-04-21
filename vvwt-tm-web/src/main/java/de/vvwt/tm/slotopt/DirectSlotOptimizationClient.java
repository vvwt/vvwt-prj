package de.vvwt.tm.slotopt;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.worker.codec.LehmerCodec;
import de.vvwt.worker.solver.PacketSolver;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.JobDef;
import de.vvwt.worker.types.PacketResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-process exhaustive slot-optimization client (E04S03).
 *
 * <p>Implements {@link SlotOptimizationClient} using the {@link PacketSolver} compute kernel from
 * {@code vvwt-worker-lib}. For phases with N <= {@code tm.slotopt.exhaustive-max-n} rows, it
 * exhaustively searches all N! permutations and applies the globally optimal result.
 *
 * <h2>Architecture note — N = rowCount</h2>
 *
 * <p>The {@link PacketSolver} operates on permutations of row indices {@code [0, rowCount)}. The
 * permutation length {@code n} passed in {@link JobDef} must therefore equal {@code
 * canonicalPhaseDef.rowCount()}, not {@code avatarCount}. The threshold check and the search space
 * are both computed from {@code rowCount}.
 *
 * <h2>Slot assignment</h2>
 *
 * <p>After finding the optimal row ordering, matches are assigned to laps using a greedy
 * round-constraint algorithm: for each match in the optimal order, the match is placed in the
 * earliest lap where neither of its avatars has already been assigned. Field numbers within a lap
 * are assigned in order of arrival. This guarantees the round constraint (AC5 / AC11: no avatar
 * plays twice in the same lap) without requiring {@link SlotResultApplicator}'s circle-method.
 *
 * <h2>DEC-4 V1 amendment</h2>
 *
 * <p>Per DEC-4 V1 amendment (2026-04-12), the Tournament Manager is permitted to call {@link
 * PacketSolver#solvePacket(JobDef, long, long)} in-process. The dispatcher HTTP integration is
 * deferred to a future Epic.
 *
 * <h2>Bean wiring</h2>
 *
 * <p>This is a {@link Service} bean of type {@link SlotOptimizationClient}. Its mere presence
 * causes Spring to skip {@link FallbackSlotOptimizationClient} due to that bean's
 * {@code @ConditionalOnMissingBean(SlotOptimizationClient.class)} annotation (AC1).
 *
 * <h2>Optimality guarantee (AC3)</h2>
 *
 * <p>For N <= {@code exhaustiveMaxN}, the result is the global optimum — the permutation with the
 * lowest variety score across all N! permutations, with ties broken by lowest rank (PacketSolver
 * deterministic tie-break per E01S03).
 *
 * <h2>Tenant scoping (AC14)</h2>
 *
 * <p>All repository calls delegate to tenant-scoped repositories (DEC-5, E03S05). The TenantContext
 * must be active before calling {@link #optimize(UUID)}.
 *
 * @see SlotOptimizationClient
 * @see FallbackSlotOptimizationClient
 * @see PacketSolver
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S03.story.md">Story
 *     E04S03</a>
 */
@Service
public class DirectSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DirectSlotOptimizationClient.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseToRawPhaseDefMapper mapper;
    private final int exhaustiveMaxN;

    /**
     * Constructs the client.
     *
     * @param phaseRepository tenant-scoped repository for Phase entities (AC7)
     * @param matchRepository tenant-scoped repository for Match entities (AC8, AC14)
     * @param mapper forward mapper for Phase → RawPhaseDef (AC2)
     * @param exhaustiveMaxN maximum N (rowCount) for exhaustive search; configured via {@code
     *     tm.slotopt.exhaustive-max-n} (AC6)
     */
    public DirectSlotOptimizationClient(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseToRawPhaseDefMapper mapper,
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
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
        this.exhaustiveMaxN = exhaustiveMaxN;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For N <= {@code tm.slotopt.exhaustive-max-n} (default: 10):
     *
     * <ol>
     *   <li>Maps the phase to {@link de.vvwt.worker.types.RawPhaseDef} via E04S02 mapper.
     *   <li>Canonicalizes via {@link de.vvwt.worker.types.StructuralFingerprint#transform}.
     *   <li>Calls {@link PacketSolver#solvePacket(JobDef, long, long)} over the full N! space,
     *       where N = rowCount (number of matches).
     *   <li>Decodes the best rank to a row permutation and assigns lap/field coordinates using a
     *       greedy round-constraint-aware algorithm.
     * </ol>
     *
     * <p>For N > {@code exhaustiveMaxN}: throws {@link UnsupportedOperationException} (AC5) until
     * E04S04 is delivered.
     *
     * <p>For N < 2: logs a warning and assigns trivial coordinates — lap 0, sequential field
     * numbers — without throwing (AC9).
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist (AC7)
     * @throws IllegalStateException if no matches exist for the phase (AC8)
     * @throws UnsupportedOperationException if N > {@code exhaustiveMaxN} and E04S04 is not yet
     *     available (AC5)
     */
    @Override
    public void optimize(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // AC7: verify phase exists (throws IAE if not found)
        phaseRepository
                .findById(phaseId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "DirectSlotOptimizationClient: phase not found: "
                                                + phaseId));

        // AC8: verify matches exist (mapper also checks, but we check early for a clearer error)
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "DirectSlotOptimizationClient: no matches found for phase "
                            + phaseId
                            + ". Cannot optimize empty phase.");
        }

        // Forward-map to RawPhaseDef + canonical form (AC2 steps 1-2)
        MappingResult mapping = mapper.map(phaseId);
        CanonicalPhaseDef canonical = mapping.canonical();

        // N = canonical.rowCount() = number of matches.
        // PacketSolver requires jobDef.n() == rowCount so that LehmerCodec.rankToPermutation(rank,
        // n)
        // produces a permutation of length rowCount, compatible with VarietyScorer.scoreWithMatrix.
        int n = canonical.rowCount();

        // AC9: trivial phase (fewer than 2 rows/matches) — assign sequential coordinates, do not
        // throw
        if (n < 2) {
            LOG.warn(
                    "DirectSlotOptimizationClient: phase={}, N={} (< 2 rows) — trivial phase,"
                            + " assigning sequential coordinates without optimization",
                    phaseId,
                    n);
            applyTrivialCoordinates(mapping);
            return;
        }

        // AC5: N > exhaustiveMaxN — timeout-based mode not yet available (E04S04)
        if (n > exhaustiveMaxN) {
            throw new UnsupportedOperationException(
                    "Exhaustive optimization not feasible for N="
                            + n
                            + " (> "
                            + exhaustiveMaxN
                            + "). "
                            + "Timeout-based mode (E04S04) not yet available.");
        }

        // AC2 step 3: build JobDef with n = rowCount
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, canonical);

        // AC2 step 4: solve the full N! permutation space (exhaustive)
        long totalPermutations = factorial(n);
        long startMs = System.currentTimeMillis();

        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, totalPermutations);

        long wallClockMs = System.currentTimeMillis() - startMs;

        // AC10: INFO log with phase ID, N, total permutations, wall-clock time, best score
        LOG.info(
                "DirectSlotOptimizationClient: phase={}, N={}, permutations={}, "
                        + "wallClockMs={}, bestScore={}",
                phaseId,
                n,
                totalPermutations,
                wallClockMs,
                result.bestScore());

        // AC2 step 5: decode the best rank to a row permutation and apply slot coordinates.
        // LehmerCodec.rankToPermutation(bestRank, n) produces an int[] rowSeq of length n=rowCount,
        // where rowSeq[position] = which match goes in that schedule position.
        int[] rowSeq = LehmerCodec.rankToPermutation(result.bestRank(), n);

        // Apply the optimized row sequence: assign lap and field numbers using a greedy
        // round-constraint-aware algorithm that respects the round constraint (AC5/AC11).
        applyOptimizedSlots(mapping, rowSeq);
    }

    // -------------------------------------------------------------------------
    // Slot assignment
    // -------------------------------------------------------------------------

    /**
     * Assigns lap/field coordinates from the optimized row sequence using a greedy
     * round-constraint-aware algorithm.
     *
     * <p>Iterates over the ordered row sequence. For each match, it is placed in the earliest lap
     * where neither of its two avatars already has a match assigned. Field numbers within each lap
     * are sequential (order of assignment).
     *
     * <p>This guarantees:
     *
     * <ul>
     *   <li>Round constraint (AC5/AC11): no avatar plays twice in the same lap — by construction.
     *   <li>Determinism (AC4/AC13): for the same best rank and same phase data, the same lap/field
     *       assignment is always produced.
     * </ul>
     *
     * @param mapping the forward mapping result (match order, denseIdsByRawRow)
     * @param rowSeq the optimized row sequence (permutation of [0, rowCount))
     */
    private void applyOptimizedSlots(MappingResult mapping, int[] rowSeq) {
        List<Match> matchOrder = mapping.matchOrder();
        int[][] denseIdsByRawRow = mapping.denseIdsByRawRow();
        int avatarCount = mapping.avatarCount();
        int rowCount = matchOrder.size();

        // Track lap assignments: for each lap, the set of dense avatar IDs already scheduled
        List<Set<Integer>> lapAvatarSets = new ArrayList<>();

        // For each match in the optimal sequence (rowSeq[position] = rowIndex in matchOrder)
        int[] assignedLap = new int[rowCount];
        int[] assignedField = new int[rowCount];
        int[] fieldCountPerLap = new int[rowCount]; // upper bound: at most rowCount laps

        for (int pos = 0; pos < rowCount; pos++) {
            int rowIdx = rowSeq[pos];
            int d1 = denseIdsByRawRow[rowIdx][0];
            int d2 = denseIdsByRawRow[rowIdx][1];

            // Find the earliest lap where neither d1 nor d2 is already assigned
            int targetLap = -1;
            for (int lap = 0; lap < lapAvatarSets.size(); lap++) {
                Set<Integer> used = lapAvatarSets.get(lap);
                if (!used.contains(d1) && !used.contains(d2)) {
                    targetLap = lap;
                    break;
                }
            }
            if (targetLap == -1) {
                // No existing lap has room — open a new lap
                targetLap = lapAvatarSets.size();
                lapAvatarSets.add(new HashSet<>());
            }

            Set<Integer> used = lapAvatarSets.get(targetLap);
            used.add(d1);
            used.add(d2);

            assignedLap[rowIdx] = targetLap;
            assignedField[rowIdx] = fieldCountPerLap[targetLap]++;
        }

        // Write lap/field to all matches (AC14: tenant-scoped via matchRepository)
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Match match = matchOrder.get(rowIdx);
            match.setLapNumber(assignedLap[rowIdx]);
            match.setFieldNumber(assignedField[rowIdx]);
            matchRepository.save(match);
        }

        int lapCount = lapAvatarSets.size();
        LOG.info(
                "DirectSlotOptimizationClient: applied optimized slots to {} matches, {} laps",
                rowCount,
                lapCount);
    }

    /**
     * Assigns trivial coordinates (lap 0, sequential field numbers) to all matches in the mapping
     * result. Used for the AC9 trivial-phase case (N < 2).
     *
     * @param mapping the forward mapping result
     */
    private void applyTrivialCoordinates(MappingResult mapping) {
        List<Match> matchOrder = mapping.matchOrder();
        for (int idx = 0; idx < matchOrder.size(); idx++) {
            Match match = matchOrder.get(idx);
            match.setLapNumber(0);
            match.setFieldNumber(idx);
            matchRepository.save(match);
        }
        LOG.info(
                "DirectSlotOptimizationClient: assigned trivial coordinates to {} matches "
                        + "in phase (N < 2 case)",
                matchOrder.size());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes N! for N in [0, 17]. All values fit in {@code long}.
     *
     * @param n the value whose factorial to compute
     * @return n!
     * @throws IllegalArgumentException if n < 0
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
