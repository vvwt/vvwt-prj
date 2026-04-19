package de.vvwt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuralFingerprint} — basic determinism, known-vector checks, and
 * input-validation paths (AC2, AC3, AC11).
 *
 * <p>Property-based collapse and no-false-collapse tests live in {@link
 * StructuralFingerprintPropertyTest} (AC4, AC5).
 */
class StructuralFingerprintTest {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    @Test
    void canonicalizationVersion_isOne() {
        assertThat(StructuralFingerprint.CANONICALIZATION_VERSION).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Canonicalization — step-by-step trace for a simple 3-row example (AC3)
    // -------------------------------------------------------------------------

    /**
     * 3 rows, each with 2 avatars. Row content (group, pos): row0: (0,0), (0,1) row1: (1,0), (1,1)
     * row2: (0,0), (1,1)
     *
     * <p>Step 1: distinct tuples sorted lex → (0,0)=0, (0,1)=1, (1,0)=2, (1,1)=3 Step 2: translate
     * rows: row0 → [0,1] row1 → [2,3] row2 → [0,3] Step 3: lex sort rows: [0,1], [0,3], [2,3] →
     * canonical rows: [[0,1],[0,3],[2,3]], avatarCount=4, rowCount=3
     */
    @Test
    void canonicalize_simpleThreeRowExample_producesExpectedCanonicalForm() {
        RawPhaseDef raw = buildSimpleThreeRowDef();

        CanonicalPhaseDef canonical = StructuralFingerprint.canonicalize(raw);

        assertThat(canonical.rowCount()).isEqualTo(3);
        assertThat(canonical.avatarCount()).isEqualTo(4);
        assertThat(canonical.rows()).containsExactly(List.of(0, 1), List.of(0, 3), List.of(2, 3));
    }

    @Test
    void transform_consistentWithSeparateCanonicalizationAndFingerprint() {
        RawPhaseDef raw = buildSimpleThreeRowDef();

        CanonicalPhaseDef canonical = StructuralFingerprint.canonicalize(raw);
        byte[] expectedFp = StructuralFingerprint.fingerprint(canonical);

        TransformResult result = StructuralFingerprint.transform(raw);

        assertThat(result.fingerprint()).isEqualTo(expectedFp);
        assertThat(result.canonical()).isEqualTo(canonical);
    }

    @Test
    void fingerprint_is32Bytes() {
        RawPhaseDef raw = buildSimpleThreeRowDef();
        TransformResult result = StructuralFingerprint.transform(raw);
        assertThat(result.fingerprint()).hasSize(32);
    }

    @Test
    void fingerprint_isDeterministic_acrossMultipleCalls() {
        RawPhaseDef raw = buildSimpleThreeRowDef();
        byte[] fp1 = StructuralFingerprint.transform(raw).fingerprint();
        byte[] fp2 = StructuralFingerprint.transform(raw).fingerprint();
        assertThat(fp1).isEqualTo(fp2);
    }

    // -------------------------------------------------------------------------
    // phaseId MUST NOT affect fingerprint (AC4 requirement; also AC13)
    // -------------------------------------------------------------------------

    @Test
    void fingerprint_differentPhaseId_sameStructure_producesIdenticalFingerprint() {
        RawPhaseDef raw1 =
                new RawPhaseDef(
                        10,
                        1,
                        List.of(
                                new RawRow(
                                        List.of(
                                                new PositionTuple(0, 0),
                                                new PositionTuple(0, 1)))));
        RawPhaseDef raw2 =
                new RawPhaseDef(
                        99,
                        1,
                        List.of(
                                new RawRow(
                                        List.of(
                                                new PositionTuple(0, 0),
                                                new PositionTuple(0, 1)))));

        assertThat(StructuralFingerprint.transform(raw1).fingerprint())
                .isEqualTo(StructuralFingerprint.transform(raw2).fingerprint());
    }

    // -------------------------------------------------------------------------
    // Row-order independence (subset of AC4)
    // -------------------------------------------------------------------------

    @Test
    void fingerprint_rowOrderPermutation_producesIdenticalFingerprint() {
        RawRow rowA = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1)));
        RawRow rowB = new RawRow(List.of(new PositionTuple(1, 0), new PositionTuple(1, 1)));

        RawPhaseDef ordered = new RawPhaseDef(0, 2, List.of(rowA, rowB));
        RawPhaseDef reordered = new RawPhaseDef(0, 2, List.of(rowB, rowA));

        assertThat(StructuralFingerprint.transform(ordered).fingerprint())
                .isEqualTo(StructuralFingerprint.transform(reordered).fingerprint());
    }

    // -------------------------------------------------------------------------
    // Input validation (AC11)
    // -------------------------------------------------------------------------

    @Test
    void transform_nullRaw_throwsIllegalArgument() {
        assertThatThrownBy(() -> StructuralFingerprint.transform(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprint_nullCanonical_throwsIllegalArgument() {
        assertThatThrownBy(() -> StructuralFingerprint.fingerprint(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC13 — no string fields in RawPhaseDef (schema conformance)
    // -------------------------------------------------------------------------

    @Test
    void rawPhaseDef_hasNoStringFields() {
        // Verify via reflection that RawPhaseDef contains no java.lang.String fields.
        // This is the static-analysis check required by AC13.
        for (var field : RawPhaseDef.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "RawPhaseDef field '%s' must not be String (AC13 PII guarantee)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
        for (var field : PositionTuple.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "PositionTuple field '%s' must not be String (AC13 PII guarantee)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
        for (var field : RawRow.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as(
                            "RawRow field '%s' must not be String (AC13 PII guarantee)",
                            field.getName())
                    .isNotEqualTo(String.class);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private RawPhaseDef buildSimpleThreeRowDef() {
        RawRow row0 = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(0, 1)));
        RawRow row1 = new RawRow(List.of(new PositionTuple(1, 0), new PositionTuple(1, 1)));
        RawRow row2 = new RawRow(List.of(new PositionTuple(0, 0), new PositionTuple(1, 1)));
        return new RawPhaseDef(42, 3, List.of(row0, row1, row2));
    }
}
