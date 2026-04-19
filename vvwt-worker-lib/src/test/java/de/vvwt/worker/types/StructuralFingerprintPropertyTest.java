package de.vvwt.worker.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for {@link StructuralFingerprint}.
 *
 * <h2>AC4 — Structural collapse property (≥1000 pairs per generator)</h2>
 *
 * Structural equivalents MUST produce the same fingerprint:
 *
 * <ul>
 *   <li>Row-order permutations
 *   <li>Within-row position-tuple reorderings
 *   <li>{@code phaseId} substitutions
 * </ul>
 *
 * <h2>AC5 — No false collapse (≥1000 random non-isomorphic pairs)</h2>
 *
 * Structurally distinct inputs MUST produce different fingerprints.
 */
class StructuralFingerprintPropertyTest {

    // =========================================================================
    // AC4 — Row-order permutations (≥1000 pairs)
    // =========================================================================

    /**
     * Given a random valid RawPhaseDef and a permutation of its rows, both must produce the same
     * fingerprint.
     */
    @Property(tries = 1000)
    void ac4_rowOrderPermutation_sameFingerprint(
            @ForAll("validRawPhaseDefs") RawPhaseDef original) {

        List<RawRow> shuffledRows = new ArrayList<>(original.rows());
        Collections.shuffle(shuffledRows);
        RawPhaseDef permuted =
                new RawPhaseDef(original.phaseId(), original.rowCount(), shuffledRows);

        assertThat(StructuralFingerprint.transform(original).fingerprint())
                .as("Row permutation must not change fingerprint")
                .isEqualTo(StructuralFingerprint.transform(permuted).fingerprint());
    }

    /**
     * Given a random valid RawPhaseDef, shuffling positions WITHIN each row must not change the
     * fingerprint (row semantics are set semantics).
     */
    @Property(tries = 1000)
    void ac4_withinRowPositionReordering_sameFingerprint(
            @ForAll("validRawPhaseDefs") RawPhaseDef original) {

        List<RawRow> reorderedRows = new ArrayList<>();
        for (RawRow row : original.rows()) {
            List<PositionTuple> shuffled = new ArrayList<>(row.positions());
            Collections.shuffle(shuffled);
            reorderedRows.add(new RawRow(shuffled));
        }
        RawPhaseDef reordered =
                new RawPhaseDef(original.phaseId(), original.rowCount(), reorderedRows);

        assertThat(StructuralFingerprint.transform(original).fingerprint())
                .as("Within-row reordering must not change fingerprint")
                .isEqualTo(StructuralFingerprint.transform(reordered).fingerprint());
    }

    /**
     * Substituting phaseId with any other value must not change the fingerprint. phaseId is
     * audit-only metadata per DEC-9 and AC1 of E01S09.
     */
    @Property(tries = 1000)
    void ac4_phaseIdSubstitution_sameFingerprint(
            @ForAll("validRawPhaseDefs") RawPhaseDef original,
            @ForAll @IntRange(min = 0, max = 9999) int alternatePhaseId) {

        RawPhaseDef withDifferentPhaseId =
                new RawPhaseDef(alternatePhaseId, original.rowCount(), original.rows());

        assertThat(StructuralFingerprint.transform(original).fingerprint())
                .as("phaseId substitution must not change fingerprint (audit-only, AC1/DEC-9)")
                .isEqualTo(StructuralFingerprint.transform(withDifferentPhaseId).fingerprint());
    }

    // =========================================================================
    // AC5 — No false collapse (≥1000 non-isomorphic pairs)
    // =========================================================================

    /**
     * Two structurally DISTINCT RawPhaseDefs (different rowCount, or different avatarCount, or
     * different multiset of row position-sets) must produce different fingerprints.
     *
     * <p>We generate guaranteed-distinct pairs by adding an extra distinct row to the second
     * definition. This ensures structural non-isomorphism.
     */
    @Property(tries = 1000)
    void ac5_structurallyDistinct_differentFingerprint(
            @ForAll("validRawPhaseDefs") RawPhaseDef base) {

        // Create a structurally distinct variant: add one extra row with a new avatar
        // that cannot appear in the original (uses a very large group number as sentinel)
        int sentinelGroup = 9999;
        RawRow extraRow = new RawRow(List.of(new PositionTuple(sentinelGroup, 0)));

        List<RawRow> extendedRows = new ArrayList<>(base.rows());
        extendedRows.add(extraRow);

        RawPhaseDef extended = new RawPhaseDef(base.phaseId(), extendedRows.size(), extendedRows);

        byte[] fpBase = StructuralFingerprint.transform(base).fingerprint();
        byte[] fpExtended = StructuralFingerprint.transform(extended).fingerprint();

        assertThat(fpBase)
                .as(
                        "Structurally distinct inputs (different rowCount) must produce different"
                                + " fingerprints")
                .isNotEqualTo(fpExtended);
    }

    // =========================================================================
    // Arbitrary generators
    // =========================================================================

    /**
     * Generates valid {@link RawPhaseDef} instances with rowCount ∈ [3, 15] and a small pool of
     * shared avatars (to ensure some cross-row sharing, which tests the multiset semantics
     * properly).
     */
    @Provide
    Arbitrary<RawPhaseDef> validRawPhaseDefs() {
        // Choose rowCount ∈ [3, 15]
        Arbitrary<Integer> rowCounts = net.jqwik.api.Arbitraries.integers().between(3, 15);

        // Choose a small avatar pool: (group ∈ [0,3], pos ∈ [0,3]) = up to 16 avatars
        Arbitrary<PositionTuple> avatarArb =
                Combinators.combine(
                                net.jqwik.api.Arbitraries.integers().between(0, 3),
                                net.jqwik.api.Arbitraries.integers().between(0, 3))
                        .as(PositionTuple::new);

        return rowCounts.flatMap(
                rowCount ->
                        net.jqwik.api.Arbitraries.integers()
                                .between(0, 999)
                                .flatMap(
                                        phaseId ->
                                                generateRows(rowCount, avatarArb)
                                                        .map(
                                                                rows ->
                                                                        new RawPhaseDef(
                                                                                phaseId, rowCount,
                                                                                rows))));
    }

    /**
     * Generates {@code rowCount} rows, each with 2–4 distinct position tuples drawn from the given
     * avatar arbitrary.
     */
    private Arbitrary<List<RawRow>> generateRows(int rowCount, Arbitrary<PositionTuple> avatarArb) {
        Arbitrary<RawRow> rowArb =
                avatarArb
                        .list()
                        .ofMinSize(2)
                        .ofMaxSize(4)
                        .map(
                                tuples -> {
                                    // Deduplicate (list-based set)
                                    List<PositionTuple> distinct =
                                            tuples.stream()
                                                    .distinct()
                                                    .collect(java.util.stream.Collectors.toList());
                                    if (distinct.isEmpty()) {
                                        // Fallback: guarantee at least 1 element
                                        distinct = List.of(new PositionTuple(0, 0));
                                    }
                                    return new RawRow(distinct);
                                });
        return rowArb.list().ofSize(rowCount);
    }
}
