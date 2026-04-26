package de.vvwt.slotopt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link RawPhaseDef}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criteria (b)
 * (correctness against analytically-known values) and (d) (named algebraic invariants quantified
 * over representative boundary inputs).
 */
@DisplayName("RawPhaseDef — Spec-Anchored algebraic invariant tests (DEC-41 D-4 replacement)")
class RawPhaseDefTest {

    // =========================================================================
    // Criterion (b): accessor-fidelity invariant
    // Invariant: new RawPhaseDef(id, count, rows).rowCount() == count
    //            AND new RawPhaseDef(id, count, rows).phaseId() == id
    //            for a representative valid input
    // =========================================================================

    @Test
    @DisplayName(
            "Criterion (b): accessor-fidelity — rowCount() and phaseId() return constructed values")
    void invariant_accessorFidelity_constructedValuesAreRetrievable() {
        RawRow row = new RawRow(List.of(new PositionTuple(0, 0)));
        RawPhaseDef def = new RawPhaseDef(42, 1, List.of(row));

        assertThat(def.rowCount())
                .as("Accessor-fidelity: rowCount() must return constructed rowCount=1")
                .isEqualTo(1);
        assertThat(def.phaseId())
                .as("Accessor-fidelity: phaseId() must return constructed phaseId=42")
                .isEqualTo(42);
    }

    // =========================================================================
    // Criterion (d): guard-clause invariants
    // Invariant: every out-of-contract input is rejected with IllegalArgumentException
    // Quantified over the complete boundary of the guard-clause domain:
    //   (a) negative rowCount, (b) rowCount/rows.size() mismatch
    // =========================================================================

    @Test
    @DisplayName("Invariant: guard-clause — negative rowCount throws IAE (lower domain boundary)")
    void invariant_guardClause_negativeRowCount_throwsIAE() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RawPhaseDef(0, -1, List.of()))
                .withMessageContaining("rowCount");
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — rowCount/rows.size() mismatch throws IAE (count-size"
                    + " invariant boundary)")
    void invariant_guardClause_rowCountMismatch_throwsIAE() {
        RawRow row = new RawRow(List.of(new PositionTuple(0, 0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RawPhaseDef(0, 2, List.of(row)))
                .withMessageContaining("rowCount");
    }

    // =========================================================================
    // Criterion (d): rowCount-rows-consistency invariant
    // Invariant: when rowCount == rows.size(), construction succeeds and rowCount() ==
    // rows().size()
    // Quantified over 3 representative sizes {0, 1, 3}
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: rowCount-rows-consistency — construction succeeds and rowCount() =="
                    + " rows().size() for sizes {0,1,3}")
    void invariant_rowCountRowsConsistency_validInputsAccepted() {
        // size=0
        RawPhaseDef empty = new RawPhaseDef(0, 0, List.of());
        assertThat(empty.rowCount())
                .as("rowCount-rows-consistency: rowCount() must equal rows().size() for size=0")
                .isEqualTo(empty.rows().size());

        // size=1
        RawRow row = new RawRow(List.of(new PositionTuple(0, 0)));
        RawPhaseDef oneRow = new RawPhaseDef(1, 1, List.of(row));
        assertThat(oneRow.rowCount())
                .as("rowCount-rows-consistency: rowCount() must equal rows().size() for size=1")
                .isEqualTo(oneRow.rows().size());

        // size=3
        List<RawRow> threeRows =
                List.of(
                        new RawRow(List.of(new PositionTuple(0, 0))),
                        new RawRow(List.of(new PositionTuple(1, 0))),
                        new RawRow(List.of(new PositionTuple(2, 0))));
        RawPhaseDef threeRow = new RawPhaseDef(99, 3, threeRows);
        assertThat(threeRow.rowCount())
                .as("rowCount-rows-consistency: rowCount() must equal rows().size() for size=3")
                .isEqualTo(threeRow.rows().size());
    }
}
