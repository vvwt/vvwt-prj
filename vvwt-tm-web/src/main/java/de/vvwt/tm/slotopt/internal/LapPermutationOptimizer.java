package de.vvwt.tm.slotopt.internal;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.Scorer;
import de.vvwt.slotopt.worker.score.ScorerFactory;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single point of correctness for the L3 exhaustive lap-permutation brute-force algorithm (E54S13 —
 * extract-and-delegate refactor).
 *
 * <p>This class encapsulates the correct algorithm shared by all slot-optimization legs (Leg 1, Leg
 * 2 fallback, Leg 3). All three clients — {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} (Leg 1), {@link
 * de.vvwt.tm.slotopt.DirectSlotOptimizationClient} (direct/legacy), and {@link
 * de.vvwt.tm.slotopt.internal.DefaultCancelableInProcessSlotOptimizationService} (Leg 3 cancelable)
 * — delegate to this class.
 *
 * <h2>Correct algorithm (DEC-61-B + DEC-63-C + E54S12 fix)</h2>
 *
 * <ol>
 *   <li>{@code lapCount = mapping.canonical().rowCount()} — DEC-61 Clause B: post-E54S02 Mapper
 *       refactor, each RawRow is a lap-row, so {@code canonical.rowCount() = lapCount} directly.
 *       NOT {@code rowCount / fieldCount} (stale pre-DEC-61 formula, bug-class 1).
 *   <li>{@code Scorer = ScorerFactory.createScorerUnified(scorerConfig)} — DEC-63 Clause C:
 *       operator-controllable scorer selection via {@code tm.slotopt.scorer}. NOT hardcoded {@code
 *       new VarietyScorer()} (bug-class 2).
 *   <li>{@code activeMatrix} built from {@code mapping.denseIdsByRawRow()} (original lap-row order)
 *       — E54S12 fix: scorer and applicator must use the same index space. NOT {@code
 *       canonical.rows()} (lex-sorted for fingerprinting → index-space mismatch, bug-class 3).
 *   <li>π from {@link LehmerCodec#rankToPermutation(long, int)} is used as the row sequence
 *       directly — DEC-61 Clause B: no {@code pi[i/fc]*fc + i%fc} expansion (bug-class 4).
 * </ol>
 *
 * <h2>Non-Spring-managed utility (DEC-58 carve-out)</h2>
 *
 * <p>Per DEC-58 Clause A, the interface mandate applies only to Spring-managed components
 * ({@code @Service}, {@code @Component}, etc.). This class is a stateless algorithm utility, not a
 * Spring bean — the DEC-58 interface mandate does NOT apply (Clause 1 carve-out). Callers construct
 * and invoke it directly. The scorer-config is threaded through from the caller's
 * {@code @Value}-injected field.
 *
 * <h2>Cancellation (Leg 3)</h2>
 *
 * <p>The optional {@link CancellationToken} is checked between each permutation (cooperative
 * cancellation). If the token is {@code null}, cancellation is disabled (Leg 1 / direct path where
 * no cancel is needed).
 *
 * <p>The pre-loop cancellation check (cancel-before-permutation → apply rank=0 L2 baseline) remains
 * the CALLER's responsibility (e.g., in {@link DefaultCancelableInProcessSlotOptimizationService}),
 * not in this class. This class begins its loop after receiving control; it does not inspect the
 * token before the first iteration.
 *
 * <h2>Investigation cross-reference</h2>
 *
 * <p>Addresses the 8 parallel bug-sites identified in investigation {@code
 * 2026-05-11-live-cluster-discrepancy.investigation.md}: 4 bug-classes × 2 sites
 * (DirectSlotOptimizationClient + DefaultCancelableInProcessSlotOptimizationService). See E54S11
 * for investigation grounding. E54S12 fixed bug-class 3 in RoutingSlotOptimizationClient only; this
 * class generalizes the fix to all 3 clients.
 *
 * @see de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient
 * @see de.vvwt.tm.slotopt.DirectSlotOptimizationClient
 * @see de.vvwt.tm.slotopt.internal.DefaultCancelableInProcessSlotOptimizationService
 * @see SlotResultApplicator
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-61.md">DEC-61 Clause B —
 *     lapCount = canonical.rowCount()</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-63.md">DEC-63 Clause C —
 *     scorer config</a>
 * @see <a href="E54S12">E54S12 — active-matrix index-space fix (Routing only)</a>
 * @see <a href="E54S13">E54S13 — extract-and-delegate refactor (all 3 clients)</a>
 */
public final class LapPermutationOptimizer {

    private static final Logger LOG = LoggerFactory.getLogger(LapPermutationOptimizer.class);

    /** Static utility class — no instantiation. */
    private LapPermutationOptimizer() {
        throw new UnsupportedOperationException(
                "LapPermutationOptimizer is a static utility class");
    }

    /**
     * Runs the exhaustive lap-permutation brute-force search and applies the optimal result.
     *
     * <p>This is the single authoritative implementation of the L3 algorithm. All three
     * slot-optimization clients delegate to this method.
     *
     * <p><strong>Algorithm summary:</strong>
     *
     * <ol>
     *   <li>Derive {@code lapCount = mapping.canonical().rowCount()} (DEC-61 Clause B).
     *   <li>If {@code lapCount < 2}: apply identity rank (0) — L2 baseline preserved
     *       (AC-ERROR-LAPCOUNT-1-IDENTITY-PRESERVED).
     *   <li>Build {@code activeMatrix} from {@code mapping.denseIdsByRawRow()} — original lap-row
     *       order (E54S12 fix; NOT from {@code canonical.rows()}).
     *   <li>Create scorer via {@link ScorerFactory#createScorerUnified(String)} with {@code
     *       scorerConfig} (DEC-63 Clause C).
     *   <li>Iterate all {@code lapCount!} ranks via {@link LehmerCodec#rankToPermutation}: π IS the
     *       row sequence directly (DEC-61 Clause B; NO pre-DEC-61 expansion).
     *   <li>Track best rank / score. On each improvement, invoke {@code jobHandleOpt.ifPresent(h ->
     *       h.updateBestSoFar(...))} (DEC-49 D-11a).
     *   <li>Check {@code token.isCancelled()} between each permutation (cooperative cancellation).
     *       If cancelled: break with best-so-far.
     *   <li>Apply best rank via {@link SlotResultApplicator#applyResult}.
     *   <li>Return {@link OptimizationResult#completed} or {@link OptimizationResult#cancelled}.
     * </ol>
     *
     * <p><strong>Pre-loop cancel contract:</strong> If the caller has already checked the token and
     * found it pre-cancelled (e.g., {@link DefaultCancelableInProcessSlotOptimizationService}), the
     * caller handles it BEFORE invoking this method. This method does NOT check the token before
     * the first iteration — cooperative cancel begins after the first evaluation.
     *
     * @param phaseId the phase being optimized (used for logging only)
     * @param mapping the {@link MappingResult} from {@link
     *     de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper#map(UUID)} — post-E54S02 shape: one lap-row
     *     per lap
     * @param fieldCount number of courts/fields per lap; used by {@link
     *     SlotResultApplicator#applyResult}
     * @param scorerConfig {@code tm.slotopt.scorer} value ({@code "mean"} or {@code "balanced"});
     *     invalid values fall back to {@code "mean"} with WARN (DEC-63 Clause C)
     * @param applicator the result applicator; called once with the best rank
     * @param token optional cancellation token; {@code null} disables cancellation (Leg 1 / direct)
     * @param jobHandleOpt optional job handle for best-so-far reporting (DEC-49 D-11a); empty for
     *     Leg 1 / direct paths
     * @return {@link OptimizationResult#completed} on natural completion; {@link
     *     OptimizationResult#cancelled} if cancelled mid-iteration
     * @throws IllegalArgumentException if {@code phaseId}, {@code mapping}, or {@code applicator}
     *     is {@code null}
     */
    public static OptimizationResult optimize(
            UUID phaseId,
            MappingResult mapping,
            int fieldCount,
            String scorerConfig,
            SlotResultApplicator applicator,
            CancellationToken token,
            Optional<JobHandle> jobHandleOpt) {

        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (mapping == null) {
            throw new IllegalArgumentException("mapping must not be null");
        }
        if (applicator == null) {
            throw new IllegalArgumentException("applicator must not be null");
        }

        // DEC-61 Clause B: lapCount = canonical.rowCount() (post-E54S02 Mapper refactor).
        // Each RawRow is a lap-row after E54S02; canonical.rowCount() = lapCount directly.
        // NOT rowCount / fieldCount (stale pre-DEC-61 formula — bug-class 1 eliminated).
        int lapCount = mapping.canonical().rowCount();
        int avatarCount = mapping.canonical().avatarCount();

        LOG.debug(
                "LapPermutationOptimizer: phase={}, lapCount={}, fieldCount={}",
                phaseId,
                lapCount,
                fieldCount);

        // AC-ERROR-LAPCOUNT-1-IDENTITY-PRESERVED: trivial phase (< 2 laps) → identity rank.
        if (lapCount < 2) {
            LOG.debug(
                    "LapPermutationOptimizer: phase={}, lapCount={} < 2 → identity rank=0 (L2"
                            + " baseline)",
                    phaseId,
                    lapCount);
            applicator.applyResult(0L, fieldCount, mapping);
            return OptimizationResult.completed(0L, 0.0);
        }

        // DEC-63 Clause C: scorer selected via config property — NOT hardcoded new VarietyScorer().
        // "mean" (default) → VarietyScorer adapter; "balanced" → BalancedVarietyScorer.
        // Bug-class 2 eliminated.
        Scorer scorer = ScorerFactory.createScorerUnified(scorerConfig);

        // E54S12 fix: build activeMatrix from denseIdsByRawRow (original lap-row order).
        // NOT from canonical.rows() (lex-sorted → index-space mismatch with applicator).
        // Bug-class 3 eliminated.
        boolean[][] activeMatrix =
                buildActiveMatrix(mapping.denseIdsByRawRow(), lapCount, avatarCount);

        long totalPermutations = factorial(lapCount);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        boolean wasCancelled = false;

        for (long rank = 0L; rank < totalPermutations; rank++) {
            // Cooperative cancellation check (Leg 3 path; no-op when token is null)
            if (token != null && token.isCancelled()) {
                wasCancelled = true;
                break;
            }

            // DEC-61 Clause B: π from LehmerCodec IS the row sequence directly.
            // No pre-DEC-61 expansion (pi[i/fc]*fc + i%fc). Bug-class 4 eliminated.
            int[] rowSeq = LehmerCodec.rankToPermutation(rank, lapCount);

            double score = scorer.scoreWithMatrix(rowSeq, lapCount, avatarCount, activeMatrix);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
                // DEC-49 D-11a: update best-so-far for Leg 3 cancelable path
                final long capturedRank = bestRank;
                final double capturedScore = bestScore;
                jobHandleOpt.ifPresent(
                        h ->
                                h.updateBestSoFar(
                                        OptimizationResult.cancelled(capturedRank, capturedScore)));
            }
        }

        LOG.info(
                "LapPermutationOptimizer: phase={}, lapCount={}, fieldCount={}, perms={},"
                        + " bestRank={}, bestScore={}, cancelled={}",
                phaseId,
                lapCount,
                fieldCount,
                totalPermutations,
                bestRank,
                bestScore,
                wasCancelled);

        applicator.applyResult(bestRank, fieldCount, mapping);

        return wasCancelled
                ? OptimizationResult.cancelled(bestRank, bestScore)
                : OptimizationResult.completed(bestRank, bestScore);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the active-matrix from original lap-row order ({@code denseIdsByRawRow}).
     *
     * <p>This is the correct index space for the permutation-search: {@code activeMatrix[lapIdx]}
     * corresponds to original lap {@code lapIdx}, matching the lap-bucket order used by {@link
     * SlotResultApplicator#applyResult} and the rank encoding from {@link LehmerCodec}.
     *
     * <p>E54S12 root-cause fix: do NOT use {@code canonical.rows()} (lex-sorted for fingerprinting
     * — index-space mismatch with applicator's original-lap-number-index space).
     *
     * @param denseIdsByRawRow {@code [lapIdx][k]} = dense avatar ID k active in lap {@code lapIdx}
     * @param rowCount number of laps
     * @param avatarCount total avatar count
     * @return active-matrix[lapIndex][avatarId] == true iff avatar is active in that lap
     */
    private static boolean[][] buildActiveMatrix(
            int[][] denseIdsByRawRow, int rowCount, int avatarCount) {
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int lapIndex = 0; lapIndex < rowCount; lapIndex++) {
            for (int avatarId : denseIdsByRawRow[lapIndex]) {
                activeMatrix[lapIndex][avatarId] = true;
            }
        }
        return activeMatrix;
    }

    /** Computes n! for n in [0, 20]. All values fit in {@code long}. */
    private static long factorial(int n) {
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
