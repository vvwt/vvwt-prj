package de.vvwt.worker.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link LehmerCodec}.
 *
 * <p>Covers: AC4 (long arithmetic, N=17 boundary), AC6 (input validation / error paths).
 * Property-based round-trip and lex-order tests are in {@link LehmerCodecPropertyTest}.
 * Thread-safety tests are in {@link LehmerCodecConcurrencyTest}.
 */
@DisplayName("LehmerCodec — example tests (AC4, AC6)")
class LehmerCodecTest {

    // -----------------------------------------------------------------
    // AC4 — Long arithmetic, N=17 boundary
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AC4: rank 0 for n=1 returns the only permutation [0]")
    void rankZeroForNOneReturnsIdentity() {
        int[] perm = LehmerCodec.rankToPermutation(0L, 1);
        assertThat(perm).containsExactly(0);
    }

    @Test
    @DisplayName("AC4: rank 0 for n=3 returns [0,1,2] (lexicographically first)")
    void rankZeroForNThreeReturnsFirst() {
        int[] perm = LehmerCodec.rankToPermutation(0L, 3);
        assertThat(perm).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("AC4: rank 5 (3!-1) for n=3 returns [2,1,0] (lexicographically last)")
    void rankLastForNThreeReturnsLast() {
        int[] perm = LehmerCodec.rankToPermutation(5L, 3);
        assertThat(perm).containsExactly(2, 1, 0);
    }

    @Test
    @DisplayName("AC4: permutationToRank([0,1,2]) == 0")
    void identityPermutationHasRankZero() {
        long rank = LehmerCodec.permutationToRank(new int[] {0, 1, 2});
        assertThat(rank).isZero();
    }

    @Test
    @DisplayName("AC4: permutationToRank([2,1,0]) == 5 (for n=3)")
    void reversePermutationRankIsLastForNThree() {
        long rank = LehmerCodec.permutationToRank(new int[] {2, 1, 0});
        assertThat(rank).isEqualTo(5L);
    }

    @Test
    @DisplayName("AC4: N=17 rank=0 round-trips cleanly (no overflow in factorial table)")
    void n17RankZeroRoundTrip() {
        int[] perm = LehmerCodec.rankToPermutation(0L, 17);
        long rank = LehmerCodec.permutationToRank(perm);
        assertThat(rank).isZero();
    }

    @Test
    @DisplayName("AC4: N=17 highest rank (17!-1) round-trips cleanly")
    void n17HighestRankRoundTrip() {
        long maxRank = LehmerCodec.FACTORIAL[17] - 1L;
        assertThat(maxRank).isEqualTo(355_687_428_095_999L);

        int[] perm = LehmerCodec.rankToPermutation(maxRank, 17);
        assertThat(perm).hasSize(17);

        long rank = LehmerCodec.permutationToRank(perm);
        assertThat(rank).isEqualTo(maxRank);
    }

    @Test
    @DisplayName("AC4: N=17 highest-rank permutation is lexicographically last [16,15,...,0]")
    void n17HighestRankIsReverseIdentity() {
        long maxRank = LehmerCodec.FACTORIAL[17] - 1L;
        int[] perm = LehmerCodec.rankToPermutation(maxRank, 17);
        int[] expected = new int[17];
        for (int i = 0; i < 17; i++) {
            expected[i] = 16 - i;
        }
        assertThat(perm).containsExactly(expected);
    }

    @Test
    @DisplayName("AC4: FACTORIAL table has correct values at spot-check positions")
    void factorialTableSpotCheck() {
        assertThat(LehmerCodec.FACTORIAL[0]).isEqualTo(1L);
        assertThat(LehmerCodec.FACTORIAL[1]).isEqualTo(1L);
        assertThat(LehmerCodec.FACTORIAL[5]).isEqualTo(120L);
        assertThat(LehmerCodec.FACTORIAL[10]).isEqualTo(3_628_800L);
        assertThat(LehmerCodec.FACTORIAL[17]).isEqualTo(355_687_428_096_000L);
    }

    // -----------------------------------------------------------------
    // AC4 — Sample mid-range rank values (regression / sanity)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AC4: rank 1 for n=3 returns [0,2,1]")
    void rankOneForNThree() {
        int[] perm = LehmerCodec.rankToPermutation(1L, 3);
        assertThat(perm).containsExactly(0, 2, 1);
    }

    @Test
    @DisplayName("AC4: rank 3 for n=3 returns [1,2,0]")
    void rankThreeForNThree() {
        int[] perm = LehmerCodec.rankToPermutation(3L, 3);
        assertThat(perm).containsExactly(1, 2, 0);
    }

    // -----------------------------------------------------------------
    // AC6 — Input validation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AC6: n < 1 (rankToPermutation) throws IAE mentioning n < 1")
    void rankToPermutationNLessThanOneThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(0L, 0))
                .withMessageContaining("n must be at least 1");
    }

    @Test
    @DisplayName("AC6: n > 17 (rankToPermutation) throws IAE mentioning n > 17")
    void rankToPermutationNGreaterThan17Throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(0L, 18))
                .withMessageContaining("at most 17");
    }

    @Test
    @DisplayName("AC6: negative rank throws IAE")
    void rankToPermutationNegativeRankThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(-1L, 5))
                .withMessageContaining("non-negative");
    }

    @Test
    @DisplayName("AC6: rank >= n! throws IAE")
    void rankToPermutationRankTooLargeThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.rankToPermutation(6L, 3)) // 3! = 6
                .withMessageContaining("out of range");
    }

    @Test
    @DisplayName("AC6: null perm array throws IAE")
    void permutationToRankNullThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(null))
                .withMessageContaining("null");
    }

    @Test
    @DisplayName("AC6: empty perm array (length 0 → n=0 < 1) throws IAE")
    void permutationToRankEmptyArrayThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[0]))
                .withMessageContaining("n must be at least 1");
    }

    @Test
    @DisplayName("AC6: perm length > 17 throws IAE")
    void permutationToRankTooLongThrows() {
        int[] tooLong = new int[18];
        for (int i = 0; i < 18; i++) {
            tooLong[i] = i;
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(tooLong))
                .withMessageContaining("at most 17");
    }

    @Test
    @DisplayName("AC6: perm with out-of-range element throws IAE")
    void permutationToRankOutOfRangeElementThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {0, 1, 5})) // 5 >= n=3
                .withMessageContaining("out of range");
    }

    @Test
    @DisplayName("AC6: perm with duplicate element throws IAE mentioning duplicate")
    void permutationToRankDuplicateElementThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {0, 1, 1}))
                .withMessageContaining("duplicate");
    }

    @Test
    @DisplayName("AC6: perm with negative element throws IAE")
    void permutationToRankNegativeElementThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LehmerCodec.permutationToRank(new int[] {-1, 0, 1}))
                .withMessageContaining("out of range");
    }

    // -----------------------------------------------------------------
    // AC1 — API contract (shape / type checks)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AC1: rankToPermutation returns array of length n")
    void rankToPermutationReturnsCorrectLength() {
        for (int n = 1; n <= 8; n++) {
            int[] perm = LehmerCodec.rankToPermutation(0L, n);
            assertThat(perm).hasSize(n);
        }
    }

    @Test
    @DisplayName("AC1: result is a valid permutation of [0,n-1]")
    void rankToPermutationResultIsValidPermutation() {
        int n = 5;
        for (long r = 0; r < LehmerCodec.FACTORIAL[n]; r++) {
            int[] perm = LehmerCodec.rankToPermutation(r, n);
            assertThat(perm).hasSize(n).doesNotHaveDuplicates();
            for (int elem : perm) {
                assertThat(elem).isBetween(0, n - 1);
            }
        }
    }

    // -----------------------------------------------------------------
    // AC7 — Version constant is accessible and non-empty
    // -----------------------------------------------------------------

    @Test
    @DisplayName("AC7: VERSION constant is non-null and non-empty")
    void versionConstantIsNonEmpty() {
        assertThat(LehmerCodec.VERSION).isNotNull().isNotEmpty();
    }
}
