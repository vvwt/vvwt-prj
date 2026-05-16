// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.score;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.List;

/**
 * Variance-aware variety-score function for slot-optimization (E54S07 / DEC-63 Clause B; E54S10
 * HOTFIX — weighted mean+variance formula replacing pure-variance degenerate case).
 *
 * <p>Alternative cost function to {@link VarietyScorer} (MEAN-of-products, legacy port). This
 * scorer uses a <em>weighted sum of mean and variance</em> of per-avatar products as the cost:
 *
 * <pre>
 * mean     = SUM(rating_i) / avatarCount
 * variance = SUM((rating_i - mean)^2) / avatarCount
 * score    = α * mean + β * variance
 *          = 1.0 * mean + 10.0 * variance   // E54S10 HOTFIX default weights
 * </pre>
 *
 * <p>Lower score = better balance. The mean term ensures that the degenerate case where all avatars
 * have identically <em>bad</em> products (e.g., {@code {25, 25, …, 25}}, variance=0) is never
 * preferred over the case where all avatars have identically <em>good</em> products (e.g., {@code
 * {1, 1, …, 1}}, variance=0). Without the mean term, pure-variance scorers assign identical scores
 * to these two cases; the tie-break by lowest Lehmer rank then picks the identity (clustered)
 * permutation — the degenerate bug fixed by E54S10.
 *
 * <h2>Weight rationale (E54S10 HOTFIX)</h2>
 *
 * <ul>
 *   <li>α = {@value #ALPHA} (mean coefficient): ensures low-mean configurations win over high-mean
 *       when variance is tied (e.g., alternating mean=1 beats clustered mean=5).
 *   <li>β = {@value #BETA} (variance coefficient): strongly penalizes inter-avatar imbalance.
 *       {@code {4,4,…,4}} (mean=4, var=0, score=4) is preferred over {@code {25,1,…,1}} (mean≈3,
 *       var=44, score≈443) — protects against extreme outliers.
 * </ul>
 *
 * <p>These are the Discovery-recommended defaults (E54S10 story §Fix specification). Delivery may
 * adjust them with empirical justification, subject to the Scenario A2 regression-guard.
 *
 * <h2>Per-avatar computation (DEC-63 Clause B)</h2>
 *
 * <p>The per-avatar product-of-run-lengths computation is <strong>identical</strong> to {@link
 * VarietyScorer}: same active-matrix walk, same product accumulation. Both scorers delegate to
 * {@link AvatarRunRatings#computeRatings} for this shared step. Only the final aggregation differs
 * (MEAN vs α*mean+β*variance).
 *
 * <h2>Determinism (DEC-49 D-3)</h2>
 *
 * <p>Identical inputs produce bit-identical outputs. The variance computation uses only {@code *},
 * {@code +}, {@code /} on IEEE 754 doubles — deterministic on all supported platforms.
 *
 * <h2>Tie-break</h2>
 *
 * <p>Tie-breaking is the <strong>caller's responsibility</strong> per E01S03 tie-break policy
 * (lowest Lehmer rank wins on equal scores). This scorer returns the raw {@code double} score only.
 *
 * <h2>DEC-58 interface mandate N/A</h2>
 *
 * <p>This class is not annotated with {@code @Service}, {@code @Component}, or {@code @Repository}.
 * It is a static utility class instantiated via {@code new} at call sites (same pattern as {@link
 * VarietyScorer}). Per DEC-58 Clause A, the universal interface mandate applies only to
 * Spring-stereotyped beans. No interface mandate applies here (documented in E54S07 impl-report,
 * AC-GOVERNANCE-DEC-58-INTERFACE-MANDATE).
 *
 * <h2>Configuration</h2>
 *
 * <p>Selected via Spring property {@code tm.slotopt.scorer=balanced} (DEC-63 Clause C). See {@link
 * ScorerFactory} for property resolution logic.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class has no mutable state. All instances are freely shareable across threads.
 */
public final class BalancedVarietyScorer implements Scorer {

    /**
     * Mean coefficient (α) for the weighted score formula {@code α*mean + β*variance}.
     *
     * <p>E54S10 HOTFIX default value: {@value}. Ensures low-mean configurations beat high-mean when
     * variance is tied (degenerate-case fix — pure-group clustered start reordered to alternating).
     */
    static final double ALPHA = 1.0;

    /**
     * Variance coefficient (β) for the weighted score formula {@code α*mean + β*variance}.
     *
     * <p>E54S10 HOTFIX default value: {@value}. Strongly penalizes inter-avatar imbalance.
     */
    static final double BETA = 10.0;

    /**
     * Computes the balance-variety score for a given row-permutation and phase definition.
     *
     * <p>Score formula: {@code α * mean + β * variance} where mean and variance are computed over
     * per-avatar idle-run products. Lower scores are better (indicate more balanced variety
     * distribution with lower mean idle-run products).
     *
     * @param rowSequence a permutation of row indices {@code [0, phaseDef.rowCount())}; must be a
     *     valid permutation (each index in range exactly once)
     * @param phaseDef the phase definition in canonical form (dense integer avatar IDs, no UUIDs);
     *     must not be {@code null}
     * @return the balance-variety score ({@code α*mean + β*variance}; lower = more balanced)
     * @throws IllegalArgumentException if {@code phaseDef} is {@code null}, if {@code rowSequence}
     *     length does not equal {@code phaseDef.rowCount()}, if {@code rowSequence} is not a valid
     *     permutation of {@code [0, phaseDef.rowCount())}, or if any avatar index in {@code
     *     phaseDef.rows()} is negative
     */
    @Override
    public double score(int[] rowSequence, CanonicalPhaseDef phaseDef) {
        validateInputs(rowSequence, phaseDef);

        final int rowCount = phaseDef.rowCount();
        final int avatarCount = phaseDef.avatarCount();

        if (avatarCount == 0) {
            return 0.0;
        }

        final List<List<Integer>> rows = phaseDef.rows();
        boolean[][] activeMatrix = buildActiveMatrix(rows, rowCount, avatarCount);
        return scoreWithMatrix(rowSequence, rowCount, avatarCount, activeMatrix);
    }

    /**
     * Allocation-free overload: computes the balance-variety score using a pre-built active-matrix.
     *
     * <p>Score formula (E54S10 HOTFIX): {@code α * mean + β * variance} where mean and variance are
     * computed over per-avatar idle-run products (see class-level Javadoc for the rationale).
     *
     * <p>Input validation is the caller's responsibility. {@code rowSequence} must be a valid
     * permutation of {@code [0, rowCount)}, dimensions must match, and {@code activeMatrix} must
     * not be {@code null}.
     *
     * @param rowSequence a valid permutation of {@code [0, rowCount)}
     * @param rowCount number of rows; must equal {@code activeMatrix.length}
     * @param avatarCount number of avatars; must equal {@code activeMatrix[0].length}
     * @param activeMatrix {@code activeMatrix[rowIndex][avatarIndex] == true} iff avatar {@code
     *     avatarIndex} is active in row {@code rowIndex}
     * @return the balance-variety score ({@code α*mean + β*variance}; lower = more balanced)
     */
    @Override
    public double scoreWithMatrix(
            int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix) {
        if (avatarCount == 0) {
            return 0.0;
        }

        // Step 1: compute per-avatar ratings (identical to VarietyScorer per DEC-63 Clause B)
        double[] ratings =
                AvatarRunRatings.computeRatings(rowSequence, rowCount, avatarCount, activeMatrix);

        // Step 2: compute mean = SUM(rating_i) / avatarCount
        double sum = 0.0;
        for (double r : ratings) {
            sum += r;
        }
        double mean = sum / avatarCount;

        // Step 3: compute variance = SUM((rating_i - mean)^2) / avatarCount
        double varianceSum = 0.0;
        for (double r : ratings) {
            double diff = r - mean;
            varianceSum += diff * diff;
        }
        double variance = varianceSum / avatarCount;

        // Step 4 (E54S10 HOTFIX): weighted score = α * mean + β * variance
        // α=ALPHA ensures low-mean configs win over high-mean when variance is tied
        // β=BETA strongly penalizes inter-avatar imbalance
        return ALPHA * mean + BETA * variance;
    }

    /**
     * Builds the active-matrix for a phase definition.
     *
     * <p>Identical semantics to {@link VarietyScorer#buildActiveMatrix}.
     *
     * @param rows canonical rows from a {@link CanonicalPhaseDef}
     * @param rowCount number of rows
     * @param avatarCount total number of distinct avatars
     * @return active-matrix where {@code result[rowIndex][avatarIndex] == true} iff avatar is in
     *     that row
     * @throws IllegalArgumentException if any avatar index is negative
     */
    @Override
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
    // Input validation (mirrors VarietyScorer.validateInputs)
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
