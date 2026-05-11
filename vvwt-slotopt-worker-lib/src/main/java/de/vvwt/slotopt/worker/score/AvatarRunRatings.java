package de.vvwt.slotopt.worker.score;

/**
 * Shared per-avatar product-of-run-lengths computation for variety scoring (E54S07 / DEC-63 Clause
 * B).
 *
 * <p>Both {@link VarietyScorer} (MEAN aggregation) and {@link BalancedVarietyScorer} (VARIANCE
 * aggregation) compute per-avatar non-variety ratings using the identical active-matrix walk and
 * product accumulation algorithm. This utility class factors that shared computation to eliminate
 * duplication risk (AC-TEST-SCORER-PER-AVATAR-LOGIC-IDENTICAL-RED).
 *
 * <h2>Algorithm</h2>
 *
 * <p>For each avatar {@code a} in {@code [0, avatarCount)}:
 *
 * <ol>
 *   <li>Walk {@code rowSequence} in order.
 *   <li>Track consecutive runs of the same active/idle state.
 *   <li>Each time the state changes: multiply the accumulated product by the run length of the
 *       just-ended run.
 *   <li>At the end: multiply by the final run length.
 * </ol>
 *
 * <p>This reproduces the legacy {@code NonVarietyRatingBuilder} formulation (same as {@link
 * VarietyScorer#scoreWithMatrix}), using {@code double} arithmetic throughout (no {@code int}
 * overflow risk per Brief C-7).
 *
 * <h2>Allocation-free hot path</h2>
 *
 * <p>A single {@code double[avatarCount]} array is allocated per call (one allocation). All loop
 * variables inside the per-avatar loop are stack primitives.
 *
 * <h2>Design rationale</h2>
 *
 * <p>{@link VarietyScorer} is a {@code public final class} not annotated with {@code @Service} or
 * any Spring stereotype — it is constructed via {@code new} at each call site. Per DEC-58 Clause A,
 * the universal interface mandate applies only to self-created Spring components (stereotyped
 * beans). Static utility classes instantiated with {@code new} are outside DEC-58's scope. No
 * interface mandate applies here.
 */
public final class AvatarRunRatings {

    /** Private constructor — static utility class. */
    private AvatarRunRatings() {
        throw new UnsupportedOperationException("AvatarRunRatings is a static utility class");
    }

    /**
     * Computes the per-avatar product-of-run-lengths rating for a given row-permutation and
     * active-matrix.
     *
     * <p>The returned array has length {@code avatarCount}. Entry {@code a} is the non-variety
     * rating for avatar {@code a}: the product of all consecutive same-state run lengths in the
     * given row ordering.
     *
     * <p>Input validation is the caller's responsibility. Specifically: {@code rowSequence} must be
     * a valid permutation of {@code [0, rowCount)}, {@code rowCount} and {@code avatarCount} must
     * match the matrix dimensions, and {@code activeMatrix} must not be {@code null}. Violation may
     * produce incorrect results without a thrown exception.
     *
     * @param rowSequence a valid permutation of {@code [0, rowCount)}
     * @param rowCount number of rows; must equal {@code activeMatrix.length}
     * @param avatarCount number of avatars; must equal {@code activeMatrix[0].length} when {@code
     *     rowCount > 0}
     * @param activeMatrix {@code activeMatrix[rowIndex][avatarIndex] == true} iff avatar {@code
     *     avatarIndex} is active in row {@code rowIndex}
     * @return per-avatar ratings array of length {@code avatarCount}; each entry ≥ 1.0 for a
     *     non-empty sequence
     */
    public static double[] computeRatings(
            int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix) {
        double[] ratings = new double[avatarCount];

        for (int avatarIndex = 0; avatarIndex < avatarCount; avatarIndex++) {
            double product = 1.0;
            int runLength = 1;
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
            // Flush the final run
            product *= runLength;
            ratings[avatarIndex] = product;
        }

        return ratings;
    }
}
