package de.vvwt.slotopt.worker.score;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.List;

/**
 * Deterministic, overflow-safe variety-score function for slot-optimization.
 *
 * <p>Computes {@code score(rowSequence, phaseDef) → double}, where a <em>lower</em> score indicates
 * a better team-variety distribution (i.e. more evenly alternating active/idle slots across rows).
 *
 * <h2>Algorithm</h2>
 *
 * <p>For each avatar (index 0..{@code phaseDef.avatarCount()-1}):
 *
 * <ol>
 *   <li>Walk {@code rowSequence} in order.
 *   <li>Track consecutive runs of the same active/idle state.
 *   <li>Each time the state changes, multiply the accumulated product by the run length of the
 *       just-ended run.
 *   <li>At the end, multiply by the final run length.
 *   <li>This produces a <em>non-variety rating</em> for the avatar (matches the legacy
 *       NonVarietyRatingBuilder formulation, modulo the overflow fix described below; see E35S04).
 * </ol>
 *
 * <p>The total score is the sum of per-avatar ratings divided by {@code avatarCount}, matching the
 * normalization in the legacy {@code MatchDistributor.optimizeMatchSlotsForVariety} method.
 *
 * <h2>Overflow fix (Brief C-7 / E01S02 AC4)</h2>
 *
 * <p>The legacy implementation accumulates the product in {@code int}, which overflows for
 * non-trivial N. This implementation uses {@code double} arithmetic throughout, which is sufficient
 * for all practical avatar counts and row counts (N ≤ 17 per the project's Phase-1 scope). The
 * result is always finite and non-NaN for valid inputs.
 *
 * <h2>Allocation-free hot path</h2>
 *
 * <p>No per-call object allocation occurs inside the hot path. The only loop-local variables are
 * primitive {@code double}/{@code int}/{@code boolean} values on the stack.
 *
 * <h2>Version constant (Brief D-12)</h2>
 *
 * <p>{@link #SCORE_FN_VERSION} is incorporated into the cache key used by the
 * deterministic-equality cache. Any change that alters score values (algorithmic, bug fix that
 * changes output, tie-break policy change) MUST bump this constant in the same commit.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class has no mutable state. All instances are freely shareable across threads. See also
 * {@link #score(int[], CanonicalPhaseDef)} — it holds no instance state.
 *
 * <h2>Tie-break</h2>
 *
 * <p>When two permutations produce equal scores, tie-breaking is the <strong>caller's
 * responsibility</strong>. This scorer returns the raw {@code double} score only. See {@code
 * PacketSolver} (E01S03) for the tie-break policy.
 */
public final class VarietyScorer {

    /**
     * Version of this scoring function, incorporated into the deterministic-equality cache key
     * (Brief D-12). Must be bumped whenever the algorithm, any bug fix that changes scores, or any
     * tie-break policy change is introduced.
     */
    public static final int SCORE_FN_VERSION = 1;

    /**
     * Computes the variety score for a given row-permutation and phase definition.
     *
     * <p>Lower scores are better (indicate more team-variety).
     *
     * <p><strong>Tie-break is the caller's responsibility.</strong> When two permutations produce
     * equal scores, the caller must apply a secondary ordering criterion.
     *
     * @param rowSequence a permutation of row indices {@code [0, phaseDef.rowCount())}; must be a
     *     valid permutation (each index in range exactly once)
     * @param phaseDef the phase definition in canonical form (dense integer avatar IDs, no UUIDs);
     *     must not be {@code null}
     * @return the variety score (lower = better variety distribution)
     * @throws IllegalArgumentException if {@code phaseDef} is {@code null}, if {@code rowSequence}
     *     length does not equal {@code phaseDef.rowCount()}, if {@code rowSequence} is not a valid
     *     permutation of {@code [0, phaseDef.rowCount())}, or if any avatar index in {@code
     *     phaseDef.rows()} is negative
     */
    public double score(int[] rowSequence, CanonicalPhaseDef phaseDef) {
        validateInputs(rowSequence, phaseDef);

        final int rowCount = phaseDef.rowCount();
        final int avatarCount = phaseDef.avatarCount();

        // Edge case: no avatars → score is 0.0 (trivially optimal, nothing to distribute).
        if (avatarCount == 0) {
            return 0.0;
        }

        final List<List<Integer>> rows = phaseDef.rows();

        // Pre-build a boolean[rowCount][avatarCount] active-matrix from the canonical
        // phase definition. This is a single allocation proportional to the input size
        // (not per-permutation) and avoids repeated list look-ups inside the hot loop.
        // For the hot path (called once per permutation from PacketSolver), this matrix
        // is owned by the caller-level context and should be reused across calls via
        // the overload score(int[], boolean[][]) below.
        boolean[][] activeMatrix = buildActiveMatrix(rows, rowCount, avatarCount);
        return scoreWithMatrix(rowSequence, rowCount, avatarCount, activeMatrix);
    }

    /**
     * Allocation-free overload: computes the variety score using a pre-built active-matrix.
     *
     * <p>This overload is intended for callers (e.g., {@code PacketSolver}) that process many
     * permutations of the same phase and can amortize the matrix allocation by calling {@link
     * #buildActiveMatrix(List, int, int)} once per phase.
     *
     * <p>Input validation is the caller's responsibility. Specifically: {@code rowSequence} must be
     * a valid permutation of {@code [0, rowCount)}, {@code rowCount} and {@code avatarCount} must
     * match the matrix dimensions, and {@code activeMatrix} must not be {@code null}. Violation may
     * produce incorrect scores without a thrown exception.
     *
     * @param rowSequence a valid permutation of {@code [0, rowCount)}
     * @param rowCount number of rows; must equal {@code activeMatrix.length}
     * @param avatarCount number of avatars; must equal {@code activeMatrix[0].length}
     * @param activeMatrix {@code activeMatrix[rowIndex][avatarIndex] == true} iff avatar {@code
     *     avatarIndex} is active in row {@code rowIndex}
     * @return the variety score (lower = better variety distribution)
     */
    public double scoreWithMatrix(
            int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix) {
        double totalScore = 0.0;

        // Hot path: iterate over each avatar and compute its non-variety rating.
        // No allocations inside this loop — all variables are stack primitives.
        for (int avatarIndex = 0; avatarIndex < avatarCount; avatarIndex++) {
            double product = 1.0;
            int runLength = 1;
            // Sentinel: use -1 to signal "no previous state seen yet"
            // We track the current run's active/idle state explicitly.
            boolean prevActive = activeMatrix[rowSequence[0]][avatarIndex];

            for (int sequencePos = 1; sequencePos < rowCount; sequencePos++) {
                boolean currentActive = activeMatrix[rowSequence[sequencePos]][avatarIndex];
                if (currentActive == prevActive) {
                    runLength++;
                } else {
                    product *= runLength;
                    runLength = 1;
                    prevActive = currentActive;
                }
            }
            // Flush the final run (matches legacy non-variety rating accumulation: rating *
            // phaseCounter; see also E35S04 — NonVarietyRatingBuilder test fixture deleted)
            product *= runLength;
            totalScore += product;
        }

        return totalScore / avatarCount;
    }

    /**
     * Builds the active-matrix for a phase definition.
     *
     * <p>Each call allocates a new {@code boolean[rowCount][avatarCount]} array. Call this once per
     * phase and reuse the result across permutation scoring calls via {@link
     * #scoreWithMatrix(int[], int, int, boolean[][])}.
     *
     * @param rows canonical rows from a {@link CanonicalPhaseDef}; each inner list contains the
     *     dense avatar IDs active in that row
     * @param rowCount number of rows
     * @param avatarCount total number of distinct avatars
     * @return active-matrix where {@code result[rowIndex][avatarIndex] == true} iff avatar {@code
     *     avatarIndex} appears in row {@code rowIndex}
     * @throws IllegalArgumentException if any avatar index in {@code rows} is negative
     */
    public boolean[][] buildActiveMatrix(List<List<Integer>> rows, int rowCount, int avatarCount) {
        boolean[][] active = new boolean[rowCount][avatarCount];
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            for (int avatarId : rows.get(rowIndex)) {
                if (avatarId < 0) {
                    throw new IllegalArgumentException(
                            "Negative avatar index " + avatarId + " at row " + rowIndex);
                }
                active[rowIndex][avatarId] = true;
            }
        }
        return active;
    }

    // -------------------------------------------------------------------------
    // Input validation (AC7)
    // -------------------------------------------------------------------------

    private static void validateInputs(int[] rowSequence, CanonicalPhaseDef phaseDef) {
        if (phaseDef == null) {
            throw new IllegalArgumentException("phaseDef must not be null");
        }
        if (rowSequence == null) {
            throw new IllegalArgumentException("rowSequence must not be null");
        }
        final int rowCount = phaseDef.rowCount();
        if (rowSequence.length != rowCount) {
            throw new IllegalArgumentException(
                    "rowSequence.length="
                            + rowSequence.length
                            + " does not match phaseDef.rowCount="
                            + rowCount);
        }
        // Validate that rowSequence is a permutation of [0, rowCount)
        if (rowCount > 0) {
            boolean[] seen = new boolean[rowCount];
            for (int index = 0; index < rowCount; index++) {
                int value = rowSequence[index];
                if (value < 0 || value >= rowCount) {
                    throw new IllegalArgumentException(
                            "rowSequence["
                                    + index
                                    + "]="
                                    + value
                                    + " is out of range [0, "
                                    + rowCount
                                    + ")");
                }
                if (seen[value]) {
                    throw new IllegalArgumentException(
                            "rowSequence is not a valid permutation: duplicate value "
                                    + value
                                    + " at index "
                                    + index);
                }
                seen[value] = true;
            }
        }
        // Validate avatar indices in phaseDef rows (negatives caught here for public score())
        final List<List<Integer>> rows = phaseDef.rows();
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            for (int avatarId : rows.get(rowIndex)) {
                if (avatarId < 0) {
                    throw new IllegalArgumentException(
                            "Negative avatar index "
                                    + avatarId
                                    + " in phaseDef at row "
                                    + rowIndex);
                }
            }
        }
    }
}
