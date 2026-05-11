package de.vvwt.slotopt.worker.score;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.List;

/**
 * Common interface for variety-score functions used in slot-optimization (E54S07 / DEC-63).
 *
 * <p>Implemented by {@link BalancedVarietyScorer} (VARIANCE-of-products, opt-in via {@code
 * tm.slotopt.scorer=balanced}) and by a thin adapter wrapping {@link VarietyScorer}
 * (MEAN-of-products, default) produced by {@link ScorerFactory}.
 *
 * <p>{@link VarietyScorer} itself does NOT implement this interface — it is textually unchanged per
 * DEC-63 Clause A / AC-GOVERNANCE-NO-EXISTING-VARIETYSCORER-MUTATION. The {@link ScorerFactory}
 * wraps it in an adapter when the {@code mean} scorer is selected.
 *
 * <h2>DEC-58 note</h2>
 *
 * <p>Neither scorer is annotated with a Spring stereotype ({@code @Service}, {@code @Component}).
 * Both are instantiated via {@code new} through {@link ScorerFactory}. Per DEC-58 Clause A, the
 * universal interface mandate applies only to Spring-stereotyped beans. This interface is
 * introduced as a voluntary clean-code measure to unify the call site in {@code
 * RoutingSlotOptimizationClient} — not a governance obligation (documented in E54S07 impl-report,
 * AC-GOVERNANCE-DEC-58-INTERFACE-MANDATE).
 */
public interface Scorer {

    /**
     * Computes the variety score for a given row-permutation and phase definition.
     *
     * <p>Lower scores indicate better variety distribution.
     *
     * @param rowSequence a permutation of row indices {@code [0, phaseDef.rowCount())}
     * @param phaseDef the phase definition in canonical form
     * @return the score (lower = better)
     * @throws IllegalArgumentException if inputs are invalid
     */
    double score(int[] rowSequence, CanonicalPhaseDef phaseDef);

    /**
     * Allocation-free overload using a pre-built active-matrix.
     *
     * @param rowSequence a valid permutation of {@code [0, rowCount)}
     * @param rowCount number of rows
     * @param avatarCount number of avatars
     * @param activeMatrix pre-built active-matrix
     * @return the score (lower = better)
     */
    double scoreWithMatrix(
            int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix);

    /**
     * Builds the active-matrix for a phase definition.
     *
     * @param rows canonical rows
     * @param rowCount number of rows
     * @param avatarCount total number of avatars
     * @return active-matrix
     * @throws IllegalArgumentException if any avatar index is negative
     */
    boolean[][] buildActiveMatrix(List<List<Integer>> rows, int rowCount, int avatarCount);
}
