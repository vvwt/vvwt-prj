package de.vvwt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link StructuralFingerprint}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criteria (b)
 * (round-trip/correctness against analytically-known results) and (d) (named algebraic invariants
 * quantified over representative input sets).
 *
 * <p>Property-based collapse and no-false-collapse tests are in {@link
 * StructuralFingerprintPropertyTest} (Spec-Anchored-a).
 */
@DisplayName(
        "StructuralFingerprint — Spec-Anchored algebraic invariant tests (DEC-41 D-4 replacement)")
class StructuralFingerprintTest {

    // =========================================================================
    // Criterion (d): CANONICALIZATION_VERSION invariant — API stability contract
    // Invariant: CANONICALIZATION_VERSION is a positive integer
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: CANONICALIZATION_VERSION is a positive integer (API stability contract)")
    void invariant_canonicalizationVersion_isPositive() {
        assertThat(StructuralFingerprint.CANONICALIZATION_VERSION)
                .as("CANONICALIZATION_VERSION invariant: must be positive (versioning contract)")
                .isPositive();
    }

    // =========================================================================
    // Criterion (d): fingerprint length invariant
    // Invariant: transform(raw).fingerprint() always returns exactly 32 bytes (SHA-256)
    // Quantified over 3 structurally distinct inputs
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: fingerprint-length — transform always returns 32-byte SHA-256 digest for 3"
                    + " distinct inputs")
    void invariant_fingerprintLength_isAlways32Bytes() {
        RawPhaseDef[] inputs = {
            buildThreeRowDef(),
            new RawPhaseDef(0, 1, List.of(new RawRow(List.of(new PositionTuple(0, 0))))),
            new RawPhaseDef(
                    99,
                    2,
                    List.of(
                            new RawRow(List.of(new PositionTuple(0, 0))),
                            new RawRow(List.of(new PositionTuple(1, 0)))))
        };
        for (RawPhaseDef raw : inputs) {
            byte[] fp = StructuralFingerprint.transform(raw).fingerprint();
            assertThat(fp)
                    .as("Fingerprint-length invariant: SHA-256 digest must always be 32 bytes")
                    .hasSize(32);
        }
    }

    // =========================================================================
    // Criterion (d): fingerprint determinism invariant
    // Invariant: repeated calls with the same input produce bit-identical output
    // Quantified over 2 calls on the same input (pure-function contract)
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: fingerprint-determinism — repeated calls on same input produce identical"
                    + " 32-byte digest")
    void invariant_fingerprintDeterminism_repeatedCallsAreIdentical() {
        RawPhaseDef raw = buildThreeRowDef();
        byte[] fp1 = StructuralFingerprint.transform(raw).fingerprint();
        byte[] fp2 = StructuralFingerprint.transform(raw).fingerprint();
        assertThat(fp1)
                .as(
                        "Fingerprint-determinism invariant: same input must always yield identical"
                                + " digest")
                .isEqualTo(fp2);
    }

    // =========================================================================
    // Criterion (b): transform consistency invariant
    // Invariant: transform(raw).fingerprint() == fingerprint(canonicalize(raw))
    //            (the combined method is consistent with the two separate methods)
    // =========================================================================

    @Test
    @DisplayName(
            "Criterion (b): transform consistency — transform(raw).fingerprint =="
                    + " fingerprint(canonicalize(raw))")
    void invariant_transformConsistency_equalsComposedCalls() {
        RawPhaseDef raw = buildThreeRowDef();

        CanonicalPhaseDef canonical = StructuralFingerprint.canonicalize(raw);
        byte[] expectedFp = StructuralFingerprint.fingerprint(canonical);

        TransformResult result = StructuralFingerprint.transform(raw);

        assertThat(result.fingerprint())
                .as(
                        "Transform-consistency invariant: transform.fingerprint must equal"
                                + " fingerprint(canonicalize(raw))")
                .isEqualTo(expectedFp);
        assertThat(result.canonical())
                .as(
                        "Transform-consistency invariant: transform.canonical must equal"
                                + " canonicalize(raw)")
                .isEqualTo(canonical);
    }

    // =========================================================================
    // Criterion (b): canonicalization analytically-known result
    // Invariant: canonicalize maps the reference 3-row input to its analytically-derivable
    //            canonical form:
    //            distinct tuples sorted lex: (0,0)→0, (0,1)→1, (1,0)→2, (1,1)→3
    //            translated rows: [[0,1],[0,3],[2,3]] (sorted lex)
    //            avatarCount = 4, rowCount = 3
    // =========================================================================

    @Test
    @DisplayName(
            "Criterion (b): canonicalize 3-row reference — produces analytically-derivable"
                    + " canonical form")
    void invariant_canonicalize_threeRowReference_producesKnownCanonicalForm() {
        // row0: (0,0),(0,1) → IDs 0,1 → sorted: [0,1]
        // row1: (1,0),(1,1) → IDs 2,3 → sorted: [2,3]
        // row2: (0,0),(1,1) → IDs 0,3 → sorted: [0,3]
        // Row order sorted lex: [0,1] < [0,3] < [2,3]
        RawPhaseDef raw = buildThreeRowDef();

        CanonicalPhaseDef canonical = StructuralFingerprint.canonicalize(raw);

        assertThat(canonical.rowCount())
                .as("Canonicalize: rowCount must equal input rowCount=3")
                .isEqualTo(3);
        assertThat(canonical.avatarCount())
                .as("Canonicalize: avatarCount must equal number of distinct tuples=4")
                .isEqualTo(4);
        assertThat(canonical.rows())
                .as(
                        "Canonicalize: rows must be lexicographically sorted and translated to"
                                + " dense IDs")
                .containsExactly(List.of(0, 1), List.of(0, 3), List.of(2, 3));
    }

    // =========================================================================
    // Criterion (d): phaseId-exclusion invariant
    // Invariant: different phaseId values with identical structure produce the same fingerprint
    //            (phaseId is audit-only metadata, must not participate in fingerprint)
    // Quantified over 2 representative phaseId pairs
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: phaseId-exclusion — different phaseId with same structure produces"
                    + " identical fingerprint")
    void invariant_phaseIdExclusion_differentPhaseIdProducesIdenticalFingerprint() {
        RawRow row = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1)));
        RawPhaseDef raw1 = new RawPhaseDef(10, 1, List.of(row));
        RawPhaseDef raw2 = new RawPhaseDef(99, 1, List.of(row));
        RawPhaseDef raw3 = new RawPhaseDef(0, 1, List.of(row));

        byte[] fp1 = StructuralFingerprint.transform(raw1).fingerprint();
        byte[] fp2 = StructuralFingerprint.transform(raw2).fingerprint();
        byte[] fp3 = StructuralFingerprint.transform(raw3).fingerprint();

        assertThat(fp1)
                .as(
                        "PhaseId-exclusion invariant: phaseId=10 vs phaseId=99 must produce"
                                + " identical fingerprint")
                .isEqualTo(fp2);
        assertThat(fp1)
                .as(
                        "PhaseId-exclusion invariant: phaseId=10 vs phaseId=0 must produce"
                                + " identical fingerprint")
                .isEqualTo(fp3);
    }

    // =========================================================================
    // Criterion (d): row-order independence invariant
    // Invariant: permuting rows of the input produces the same fingerprint
    //            (canonicalization sorts rows — row order must not matter)
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: row-order-independence — permuting input rows produces identical"
                    + " fingerprint")
    void invariant_rowOrderIndependence_permutedRowsProduceIdenticalFingerprint() {
        RawRow rowA = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1)));
        RawRow rowB = new RawRow(List.of(new PositionTuple(1, 0), new PositionTuple(1, 1)));

        RawPhaseDef ordered = new RawPhaseDef(0, 2, List.of(rowA, rowB));
        RawPhaseDef reordered = new RawPhaseDef(0, 2, List.of(rowB, rowA));

        assertThat(StructuralFingerprint.transform(ordered).fingerprint())
                .as(
                        "Row-order-independence invariant: [rowA,rowB] and [rowB,rowA] must produce"
                                + " identical fingerprint")
                .isEqualTo(StructuralFingerprint.transform(reordered).fingerprint());
    }

    // =========================================================================
    // Criterion (d): structural-distinctness invariant
    // Invariant: structurally distinct inputs produce different fingerprints
    //            (the 3-row and 2-row variants with different topology differ)
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: structural-distinctness — structurally distinct inputs produce different"
                    + " fingerprints")
    void invariant_structuralDistinctness_differentStructuresProduceDifferentFingerprints() {
        RawPhaseDef threeRow = buildThreeRowDef();
        RawPhaseDef oneRow =
                new RawPhaseDef(42, 1, List.of(new RawRow(List.of(new PositionTuple(0, 0)))));

        byte[] fp3 = StructuralFingerprint.transform(threeRow).fingerprint();
        byte[] fp1 = StructuralFingerprint.transform(oneRow).fingerprint();

        assertThat(fp3)
                .as(
                        "Structural-distinctness invariant: 3-row and 1-row inputs must produce"
                                + " different fingerprints")
                .isNotEqualTo(fp1);
    }

    // =========================================================================
    // Criterion (d): no-string-fields invariant (PII isolation)
    // Invariant: RawPhaseDef, PositionTuple, and RawRow have no java.lang.String fields
    //            (DEC-9 PII isolation guarantee)
    // Quantified over all declared fields of the three record types
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: no-string-fields — RawPhaseDef, PositionTuple, RawRow have no String fields"
                    + " (DEC-9 PII isolation)")
    void invariant_noStringFields_rawTypesHaveNoPiiFields() {
        for (var field : RawPhaseDef.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "No-string-fields invariant: RawPhaseDef field '%s' must not be String"
                                    + " (DEC-9)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
        for (var field : PositionTuple.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "No-string-fields invariant: PositionTuple field '%s' must not be"
                                    + " String (DEC-9)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
        for (var field : RawRow.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "No-string-fields invariant: RawRow field '%s' must not be String"
                                    + " (DEC-9)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
    }

    // =========================================================================
    // Criterion (d): guard-clause invariants
    // Invariant: every out-of-contract input is rejected with IllegalArgumentException
    // =========================================================================

    @Test
    @DisplayName("Invariant: guard-clause — transform(null) throws IAE (null domain boundary)")
    void invariant_guardClause_transformNullRaw_throwsIAE() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StructuralFingerprint.transform(null));
    }

    @Test
    @DisplayName("Invariant: guard-clause — fingerprint(null) throws IAE (null canonical boundary)")
    void invariant_guardClause_fingerprintNullCanonical_throwsIAE() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StructuralFingerprint.fingerprint(null));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Reference 3-row RawPhaseDef: row0: (0,0),(0,1) row1: (1,0),(1,1) row2: (0,0),(1,1) Distinct
     * tuples sorted lex: (0,0)→0, (0,1)→1, (1,0)→2, (1,1)→3 Canonical rows (lex sorted):
     * [[0,1],[0,3],[2,3]]
     */
    private static RawPhaseDef buildThreeRowDef() {
        RawRow row0 = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1)));
        RawRow row1 = new RawRow(List.of(new PositionTuple(1, 0), new PositionTuple(1, 1)));
        RawRow row2 = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(1, 1)));
        return new RawPhaseDef(42, 3, List.of(row0, row1, row2));
    }
}
