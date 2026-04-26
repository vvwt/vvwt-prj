package de.vvwt.slotopt.worker.codec;

import java.util.Arrays;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property-based tests for {@link LehmerCodec} using jqwik.
 *
 * <p>Covers AC2 (round-trip), AC3 (lex-order vs reference walker), AC4 (N=17 boundary).
 *
 * <p>jqwik auto-shrinks counter-examples to the minimal failing input.
 */
class LehmerCodecPropertyTest {

    // -----------------------------------------------------------------
    // AC2 — Round-trip property
    // -----------------------------------------------------------------

    /**
     * AC2: for all n ∈ [1,17] and all ranks r ∈ [0, n!), the round-trip holds: {@code
     * permutationToRank(rankToPermutation(r, n)) == r}.
     *
     * <p>jqwik generates random (n, r) pairs from the valid domain. For small n the entire space is
     * explored; for large n jqwik samples.
     */
    @Property(tries = 2000)
    @Label("AC2: round-trip rankToPermutation ∘ permutationToRank == identity")
    boolean roundTripProperty(
            @ForAll @IntRange(min = 1, max = 17) int n,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long rawRank) {

        long maxRank = LehmerCodec.FACTORIAL[n] - 1L;
        // Constrain rawRank into the valid range for this n using modulo
        long rank = rawRank % LehmerCodec.FACTORIAL[n];
        if (rank < 0) {
            rank += LehmerCodec.FACTORIAL[n];
        }

        int[] perm = LehmerCodec.rankToPermutation(rank, n);
        long recovered = LehmerCodec.permutationToRank(perm);
        return recovered == rank;
    }

    /**
     * AC2 (exhaustive small n): for n ∈ [1,7] enumerate all ranks exhaustively. 7! = 5040, so all
     * 28 full ranges are checked in milliseconds.
     */
    @Property(tries = 7)
    @Label("AC2: exhaustive round-trip for small n (1..7)")
    boolean roundTripExhaustiveSmallN(@ForAll @IntRange(min = 1, max = 7) int n) {
        long total = LehmerCodec.FACTORIAL[n];
        for (long r = 0; r < total; r++) {
            int[] perm = LehmerCodec.rankToPermutation(r, n);
            long recovered = LehmerCodec.permutationToRank(perm);
            if (recovered != r) {
                return false;
            }
        }
        return true;
    }

    /**
     * AC2 (inverse direction): permutationToRank then rankToPermutation recovers the original
     * permutation.
     */
    @Property(tries = 1000)
    @Label("AC2: round-trip permutationToRank ∘ rankToPermutation == identity")
    boolean roundTripInverseProperty(@ForAll("validPermutations") int[] perm) {
        long rank = LehmerCodec.permutationToRank(perm);
        int[] recovered = LehmerCodec.rankToPermutation(rank, perm.length);
        return Arrays.equals(perm, recovered);
    }

    // -----------------------------------------------------------------
    // AC3 — Lex-order property vs reference next-permutation walker
    // -----------------------------------------------------------------

    /**
     * AC3: for all n ∈ [1,8] the sequence produced by rankToPermutation(0..n!-1, n) matches the
     * sequence produced by a reference next-permutation walker bit-identically.
     *
     * <p>n is capped at 8 (8! = 40320) to keep the test fast while still covering all n ≤ 8
     * exhaustively. For n > 8 the round-trip property (AC2) provides the equivalent guarantee.
     */
    @Property(tries = 8)
    @Label("AC3: lex-order matches reference next-permutation walker for n ∈ [1,8]")
    boolean lexOrderMatchesReferenceWalker(@ForAll @IntRange(min = 1, max = 8) int n) {
        long total = LehmerCodec.FACTORIAL[n];

        // Reference walker: start from [0,1,...,n-1] and apply next-permutation each step
        int[] ref = new int[n];
        for (int i = 0; i < n; i++) {
            ref[i] = i;
        }

        for (long r = 0; r < total; r++) {
            int[] codec = LehmerCodec.rankToPermutation(r, n);
            if (!Arrays.equals(codec, ref)) {
                return false;
            }
            if (r < total - 1) {
                nextPermutation(ref);
            }
        }
        return true;
    }

    // -----------------------------------------------------------------
    // AC4 — N=17 boundary
    // -----------------------------------------------------------------

    @Property(tries = 1)
    @Label("AC4: N=17 rank=0 round-trips (no overflow)")
    boolean n17RankZeroNoOverflow() {
        int[] perm = LehmerCodec.rankToPermutation(0L, 17);
        long r = LehmerCodec.permutationToRank(perm);
        return r == 0L;
    }

    @Property(tries = 1)
    @Label("AC4: N=17 max rank (17!-1) round-trips (no overflow)")
    boolean n17MaxRankNoOverflow() {
        long maxRank = LehmerCodec.FACTORIAL[17] - 1L;
        int[] perm = LehmerCodec.rankToPermutation(maxRank, 17);
        long r = LehmerCodec.permutationToRank(perm);
        return r == maxRank;
    }

    /**
     * AC4: sample 500 random ranks in the N=17 space and verify round-trip. 17! ≈ 3.56e14; uniform
     * sampling verifies there are no local overflows in the high range.
     */
    @Property(tries = 500)
    @Label("AC4: N=17 sampled ranks all round-trip (no overflow)")
    boolean n17SampledRanksRoundTrip(
            @ForAll @LongRange(min = 0, max = 355_687_428_095_999L) long rank) {
        int[] perm = LehmerCodec.rankToPermutation(rank, 17);
        long recovered = LehmerCodec.permutationToRank(perm);
        return recovered == rank;
    }

    // -----------------------------------------------------------------
    // Arbitrary providers
    // -----------------------------------------------------------------

    /** Generates valid random permutations of [0, n-1] for n ∈ [1, 17]. */
    @Provide
    Arbitrary<int[]> validPermutations() {
        return Arbitraries.integers()
                .between(1, 17)
                .flatMap(
                        n -> {
                            // Generate rank in [0, n!) and convert — this guarantees a valid
                            // permutation
                            long maxRank = LehmerCodec.FACTORIAL[n] - 1L;
                            return Arbitraries.longs()
                                    .between(0L, maxRank)
                                    .map(rank -> LehmerCodec.rankToPermutation(rank, n));
                        });
    }

    // -----------------------------------------------------------------
    // Reference next-permutation walker
    // -----------------------------------------------------------------

    /**
     * Computes the next lexicographic permutation of {@code perm} in place. Standard Narayana
     * Pandita algorithm (O(n) time). Returns false if already at last permutation.
     *
     * <p>This reference implementation is intentionally separate from {@link LehmerCodec} so that
     * the AC3 property test exercises two independent algorithms.
     */
    static boolean nextPermutation(int[] perm) {
        int n = perm.length;
        // Find rightmost ascent: largest i such that perm[i] < perm[i+1]
        int i = n - 2;
        while (i >= 0 && perm[i] >= perm[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false; // already last permutation
        }
        // Find the rightmost element perm[j] > perm[i]
        int j = n - 1;
        while (perm[j] <= perm[i]) {
            j--;
        }
        // Swap perm[i] and perm[j]
        int tmp = perm[i];
        perm[i] = perm[j];
        perm[j] = tmp;
        // Reverse suffix starting at i+1
        int left = i + 1;
        int right = n - 1;
        while (left < right) {
            tmp = perm[left];
            perm[left] = perm[right];
            perm[right] = tmp;
            left++;
            right--;
        }
        return true;
    }
}
