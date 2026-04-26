package de.vvwt.slotopt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link PositionTuple}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criteria (b)
 * (correctness against analytically-known values) and (d) (named algebraic invariants quantified
 * over representative boundary inputs).
 */
@DisplayName("PositionTuple — Spec-Anchored algebraic invariant tests (DEC-41 D-4 replacement)")
class PositionTupleTest {

    // =========================================================================
    // Criterion (b): accessor-fidelity invariant
    // Invariant: new PositionTuple(g, p).group() == g AND new PositionTuple(g, p).pos() == p
    //            for representative non-negative values
    // Quantified over 3 representative (group, pos) pairs
    // =========================================================================

    @Test
    @DisplayName(
            "Criterion (b): accessor-fidelity — group() and pos() return the constructed values for"
                    + " representative inputs")
    void invariant_accessorFidelity_constructedValuesAreRetrievable() {
        int[][] inputs = {{0, 0}, {1, 5}, {100, 200}};
        for (int[] gp : inputs) {
            PositionTuple tuple = new PositionTuple(gp[0], gp[1]);
            assertThat(tuple.group())
                    .as("Accessor-fidelity: group() must return constructed group=%d", gp[0])
                    .isEqualTo(gp[0]);
            assertThat(tuple.pos())
                    .as("Accessor-fidelity: pos() must return constructed pos=%d", gp[1])
                    .isEqualTo(gp[1]);
        }
    }

    // =========================================================================
    // Criterion (d): guard-clause invariants
    // Invariant: negative group or pos is rejected with IllegalArgumentException
    // Quantified over the complete boundary (negative group, negative pos)
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: guard-clause — negative group throws IAE (lower group domain boundary)")
    void invariant_guardClause_negativeGroup_throwsIAE() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PositionTuple(-1, 0))
                .withMessageContaining("group");
    }

    @Test
    @DisplayName("Invariant: guard-clause — negative pos throws IAE (lower pos domain boundary)")
    void invariant_guardClause_negativePos_throwsIAE() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PositionTuple(0, -1))
                .withMessageContaining("pos");
    }
}
