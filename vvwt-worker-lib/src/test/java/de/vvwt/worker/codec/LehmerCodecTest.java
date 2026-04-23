package de.vvwt.worker.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link LehmerCodec}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01) with fresh TDD tests under
 * DEC-22 Iron Law. All tests satisfy DEC-41 criterion (b) (round-trip bijection) or criterion (d)
 * (named algebraic invariant quantified over a representative or exhaustive input set).
 *
 * <p>Property-based round-trip and lex-order tests are in {@link LehmerCodecPropertyTest}.
 * Concurrency tests are in {@link LehmerCodecConcurrencyTest}.
 */
@DisplayName("LehmerCodec — Spec-Anchored algebraic invariant tests (DEC-41 D-4 replacement)")
class LehmerCodecTest {

    // -------------------------------------------------------------------------
    // Criterion (b): round-trip bijection invariant
    // Invariant: permutationToRank(rankToPermutation(r, n)) == r  for all r in [0, n!-1]
    // Quantified over all 6 permutations of n=3 and both boundaries of n=17
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Bijection: permutationToRank(rankToPermutation(r, 3)) == r for all r in [0,5]")
    void bijection_roundTrip_allPermutationsN3() {
        int n = 3;
        for (long r = 0; r < LehmerCodec.FACTORIAL[n]; r++) {
            int[] perm = LehmerCodec.rankToPermutation(r, n);
            long recovered = LehmerCodec.permutationToRank(perm);
            assertThat(recovered)
                    .as("Round-trip bijection must hold for n=%d r=%d", n, r)
                    .isEqualTo(r);
        }
    }

    @Test
    @DisplayName(
            "Bijection: rankToPermutation(permutationToRank(p), 4) == p for all 24 permutations of"
                    + " n=4")
    void bijection_roundTrip_allPermutationsN4_reverseDirection() {
        int n = 4;
        for (long r = 0; r < LehmerCodec.FACTORIAL[n]; r++) {
            int[] perm = LehmerCodec.rankToPermutation(r, n);
            long rank = LehmerCodec.permutationToRank(perm);
            int[] recovered = LehmerCodec.rankToPermutation(rank, n);
            assertThat(recovered)
                    .as("Reverse round-trip bijection must hold for n=%d r=%d", n, r)
                    .containsExactly(perm);
        }
    }

    @Test
    @DisplayName("Bijection: N=17 boundary — rank=0 and rank=17!-1 round-trip correctly")
    void bijection_n17_boundaryRanks_roundTrip() {
        // rank=0: lex-smallest permutation [0,1,...,16]
        int[] permAtZero = LehmerCodec.rankToPermutation(0L, 17);
        assertThat(LehmerCodec.permutationToRank(permAtZero))
                .as("N=17 rank=0 round-trip must return 0 (bijection boundary)")
                .isZero();

        // rank=17!-1: lex-largest permutation [16,15,...,0]
        long maxRank = LehmerCodec.FACTORIAL[17] - 1L;
        int[] permAtMax = LehmerCodec.rankToPermutation(maxRank, 17);
        assertThat(LehmerCodec.permutationToRank(permAtMax))
                .as("N=17 max-rank round-trip must return 17!-1 (bijection boundary)")
                .isEqualTo(maxRank);
    }

    // -------------------------------------------------------------------------
    // Criterion (d): lex-order monotonicity invariant
    // Invariant: rankToPermutation(r, n) <lex rankToPermutation(r+1, n) for all r in [0, n!-2]
    // Quantified over all consecutive rank pairs for n=5 (120 pairs)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: lex-order monotonicity — rank(r) <lex rank(r+1) for all consecutive pairs,"
                    + " n=5")
    void invariant_lexOrderMonotonicity_allConsecutivePairsN5() {
        int n = 5;
        long nFactorial = LehmerCodec.FACTORIAL[n];
        for (long r = 0; r < nFactorial - 1; r++) {
            int[] permR = LehmerCodec.rankToPermutation(r, n);
            int[] permRPlus1 = LehmerCodec.rankToPermutation(r + 1, n);
            assertThat(lexicographicCompare(permR, permRPlus1))
                    .as(
                            "Lex-order monotonicity invariant: perm[r=%d] must be lex-less-than"
                                    + " perm[r+1=%d]",
                            r, r + 1)
                    .isNegative();
        }
    }

    // -------------------------------------------------------------------------
    // Criterion (d): valid-permutation invariant
    // Invariant: rankToPermutation(r, n) is always a valid permutation of [0, n-1]
    //            (no duplicates, all elements in [0, n-1], length exactly n)
    // Quantified over all ranks for n in [1, 6]
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: valid-permutation — output is always a valid permutation of [0, n-1] for n"
                    + " in [1,6]")
    void invariant_validPermutation_allRanksNOneToNSix() {
        for (int n = 1; n <= 6; n++) {
            long nFactorial = LehmerCodec.FACTORIAL[n];
            for (long r = 0; r < nFactorial; r++) {
                int[] perm = LehmerCodec.rankToPermutation(r, n);
                assertThat(perm)
                        .as("Valid-permutation: length must be n=%d for rank=%d", n, r)
                        .hasSize(n)
                        .doesNotHaveDuplicates();
                for (int elem : perm) {
                    assertThat(elem)
                            .as(
                                    "Valid-permutation: element must be in [0, n-1=%d] for n=%d"
                                            + " rank=%d",
                                    n - 1, n, r)
                            .isBetween(0, n - 1);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Criterion (d): factorial-recurrence invariant
    // Invariant: FACTORIAL[n] = n * FACTORIAL[n-1] for all n in [1,17]  AND  FACTORIAL[0] = 1
    // Quantified over n in [0, 17] (exhaustive)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: factorial-recurrence — FACTORIAL[n] = n * FACTORIAL[n-1] for all n in"
                    + " [0,17]")
    void invariant_factorialTableRecurrence_fullRange() {
        assertThat(LehmerCodec.FACTORIAL[0])
                .as("Factorial base-case invariant: FACTORIAL[0] must equal 1 (empty product)")
                .isEqualTo(1L);
        for (int n = 1; n <= 17; n++) {
            assertThat(LehmerCodec.FACTORIAL[n])
                    .as(
                            "Factorial recurrence invariant: FACTORIAL[%d] must equal %d *"
                                    + " FACTORIAL[%d]",
                            n, n, n - 1)
                    .isEqualTo((long) n * LehmerCodec.FACTORIAL[n - 1]);
        }
    }

    // -------------------------------------------------------------------------
    // Criterion (d): surjection invariant
    // Invariant: permutationToRank maps permutations of [0,n-1] surjectively onto [0, n!-1]
    // Quantified over all 24 permutations of n=4
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Invariant: surjection — permutationToRank covers every rank in [0, n!-1] for n=4")
    void invariant_surjection_permutationToRankCoversAllRanks_n4() {
        int n = 4;
        long nFactorial = LehmerCodec.FACTORIAL[n];
        boolean[] seen = new boolean[(int) nFactorial];
        for (long r = 0; r < nFactorial; r++) {
            int[] perm = LehmerCodec.rankToPermutation(r, n);
            long rank = LehmerCodec.permutationToRank(perm);
            seen[(int) rank] = true;
        }
        for (int r = 0; r < nFactorial; r++) {
            assertThat(seen[r])
                    .as(
                            "Surjection invariant: rank=%d must be reachable via permutationToRank"
                                    + " for n=%d",
                            r, n)
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Criterion (d): guard-clause invariant
    // Invariant: every out-of-contract input is rejected with IllegalArgumentException
    // Quantified over the complete boundary of the guard-clause domain
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: guard-clause — n < 1 in rankToPermutation throws IAE (lower domain"
                    + " boundary)")
    void invariant_guardClause_rankToPermutation_nZero() {
        assertThatIllegalArgumentException().isThrownBy(() -> LehmerCodec.rankToPermutation(0L, 0));
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — n > 17 in rankToPermutation throws IAE (upper domain"
                    + " boundary)")
    void invariant_guardClause_rankToPermutation_nEighteen() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(0L, 18));
    }

    @Test
    @DisplayName("Invariant: guard-clause — negative rank throws IAE (rank domain lower boundary)")
    void invariant_guardClause_rankToPermutation_negativeRank() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(-1L, 5));
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — rank == n! throws IAE for all n in [1,5] (rank domain upper"
                    + " boundary)")
    void invariant_guardClause_rankToPermutation_rankExactlyNFactorial_allN() {
        for (int n = 1; n <= 5; n++) {
            final int fn = n;
            assertThatIllegalArgumentException()
                    .as("Guard-clause: rank == %d! must throw IAE for n=%d", n, n)
                    .isThrownBy(() -> LehmerCodec.rankToPermutation(LehmerCodec.FACTORIAL[fn], fn));
        }
    }

    @Test
    @DisplayName("Invariant: guard-clause — null array in permutationToRank throws IAE")
    void invariant_guardClause_permutationToRank_nullArray() {
        assertThatIllegalArgumentException().isThrownBy(() -> LehmerCodec.permutationToRank(null));
    }

    @Test
    @DisplayName("Invariant: guard-clause — empty array in permutationToRank throws IAE (n=0 < 1)")
    void invariant_guardClause_permutationToRank_emptyArray() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[0]));
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — length-18 array in permutationToRank throws IAE (n > 17)")
    void invariant_guardClause_permutationToRank_tooLong() {
        int[] tooLong = new int[18];
        for (int i = 0; i < 18; i++) tooLong[i] = i;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(tooLong));
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — duplicate element in permutationToRank throws IAE for"
                    + " representative cases")
    void invariant_guardClause_permutationToRank_duplicateElement_representativeCases() {
        // Quantified over 5 representative duplicate patterns of length 3
        int[][] duplicateCases = {{0, 0, 1}, {0, 1, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 1}};
        for (int[] dup : duplicateCases) {
            assertThatIllegalArgumentException()
                    .as(
                            "Guard-clause: duplicate in %s must throw IAE",
                            java.util.Arrays.toString(dup))
                    .isThrownBy(() -> LehmerCodec.permutationToRank(dup));
        }
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — out-of-range element in permutationToRank throws IAE"
                    + " (negative, == n, > n)")
    void invariant_guardClause_permutationToRank_outOfRange_boundaryPattern() {
        // Quantified over three boundary patterns (negative, exact boundary, over boundary)
        assertThatIllegalArgumentException()
                .as("Guard-clause: negative element must throw IAE")
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {-1, 0, 1}));
        assertThatIllegalArgumentException()
                .as("Guard-clause: element == n=3 must throw IAE")
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {0, 1, 3}));
        assertThatIllegalArgumentException()
                .as("Guard-clause: element > n=3 must throw IAE")
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {0, 1, 5}));
    }

    // -------------------------------------------------------------------------
    // Criterion (d): VERSION constant non-emptiness invariant
    // Invariant: the VERSION string is non-null and non-empty (API stability contract)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Invariant: VERSION constant is non-null and non-empty (API stability contract)")
    void invariant_versionConstant_nonNullAndNonEmpty() {
        assertThat(LehmerCodec.VERSION)
                .as("VERSION constant invariant: must be non-null and non-empty")
                .isNotNull()
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Lexicographic comparison of two int arrays of equal length. */
    private static int lexicographicCompare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}
