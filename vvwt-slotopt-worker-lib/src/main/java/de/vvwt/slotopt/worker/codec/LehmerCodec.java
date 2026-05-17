// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lehmer-code permutation codec.
 *
 * <p>Provides deterministic, random-access conversion between rank (in lexicographic order) and
 * permutations of N elements, using the factorial number system (Lehmer code).
 *
 * <h2>Mathematical contract</h2>
 *
 * <ul>
 *   <li>{@link #rankToPermutation(long, int)} maps a rank {@code r ∈ [0, n!)} to the unique
 *       permutation of {@code [0, n-1]} that is the r-th in lexicographic order (0-indexed).
 *   <li>{@link #permutationToRank(int[])} is the inverse: given a valid permutation of {@code [0,
 *       n-1]} it returns its 0-based lexicographic rank.
 *   <li>Round-trip guarantee: {@code permutationToRank(rankToPermutation(r, n)) == r} for all
 *       {@code n ∈ [1, 17]} and {@code r ∈ [0, n!)}.
 *   <li>Lex-order guarantee: the sequence produced by increasing rank matches a reference
 *       next-permutation walker bit-identically.
 * </ul>
 *
 * <h2>Capacity</h2>
 *
 * <p>Supports {@code n ∈ [1, 17]}. At n=17, the highest rank is {@code 17! - 1 =
 * 355_687_428_095_999}, which fits comfortably in a {@code long} (max {@code long} ≈ 9.22 × 10^18).
 * No overflow is possible in intermediate factorial computations when the precomputed table is
 * used.
 *
 * <h2>Thread-safety</h2>
 *
 * <p>This class is unconditionally thread-safe. All state is either immutable ({@code FACTORIAL})
 * or local to the calling stack frame. No locking is required.
 *
 * <h2>Observability</h2>
 *
 * <p>Emits exactly one {@code INFO}-level log line per JVM on first construction of a permutation
 * result, carrying the {@link #VERSION} constant for forensic traceability. No logging occurs in
 * the hot path after the first call.
 *
 * <h2>Story</h2>
 *
 * <p>Implements story E01S01 (AC1–AC8) in {@code vvwt-worker-lib} per DEC-11.
 */
public final class LehmerCodec {

    /** Version constant for forensic traceability (AC7). Increment on any algorithmic change. */
    public static final String VERSION = "1.0.0";

    private static final Logger LOG = LoggerFactory.getLogger(LehmerCodec.class);

    /** Maximum supported element count. 18! overflows long; 17! = 355_687_428_096_000 is safe. */
    public static final int MAX_N = 17;

    /**
     * Precomputed factorials 0! through 17!. FACTORIAL[i] = i! as a long. Index 0 = 1 (0! = 1 by
     * convention). Overflow is impossible: FACTORIAL[17] = 355_687_428_096_000 &lt;&lt;
     * Long.MAX_VALUE.
     */
    static final long[] FACTORIAL = new long[MAX_N + 1];

    static {
        FACTORIAL[0] = 1L;
        for (int i = 1; i <= MAX_N; i++) {
            FACTORIAL[i] = FACTORIAL[i - 1] * i;
        }
    }

    /** Sentinel to ensure the INFO log fires exactly once (AC7). */
    private static volatile boolean firstUseLogged = false;

    /** Private constructor — this is a pure-static utility class. */
    private LehmerCodec() {
        throw new UnsupportedOperationException("LehmerCodec is a utility class");
    }

    /**
     * Converts a 0-based lexicographic rank to the corresponding permutation of {@code [0, n-1]}.
     *
     * <p>Algorithm: factoradic (Lehmer code) decomposition.
     *
     * <ol>
     *   <li>Compute the Lehmer code digits {@code d[0..n-1]}: {@code d[i] = rank / (n-1-i)!}, then
     *       {@code rank %= (n-1-i)!}.
     *   <li>Reconstruct the permutation from the Lehmer code: maintain a list of remaining
     *       elements; {@code perm[i] = remaining.remove(d[i])}.
     * </ol>
     *
     * <p>This method is O(n²) time and O(n) space.
     *
     * @param rank 0-based lexicographic rank; must satisfy {@code 0 ≤ rank < n!}
     * @param n number of elements; must satisfy {@code 1 ≤ n ≤ 17}
     * @return permutation of {@code [0, n-1]} in lexicographic order position {@code rank}
     * @throws IllegalArgumentException if {@code n < 1}, {@code n > 17}, {@code rank < 0}, or
     *     {@code rank >= n!}
     */
    public static int[] rankToPermutation(long rank, int n) {
        validateN(n);
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be non-negative, got: " + rank);
        }
        long maxRank = FACTORIAL[n] - 1L;
        if (rank > maxRank) {
            throw new IllegalArgumentException(
                    "rank "
                            + rank
                            + " is out of range for n="
                            + n
                            + "; maximum valid rank is "
                            + maxRank
                            + " ("
                            + n
                            + "! - 1)");
        }

        logFirstUse();

        // Build Lehmer code digits
        int[] digits = new int[n];
        long remaining = rank;
        for (int i = 0; i < n; i++) {
            long factorial = FACTORIAL[n - 1 - i];
            digits[i] = (int) (remaining / factorial);
            remaining %= factorial;
        }

        // Reconstruct permutation from Lehmer code
        // 'available' tracks which elements [0..n-1] have not yet been placed
        // Use a boolean array + linear search for O(n) per step → O(n²) overall.
        boolean[] used = new boolean[n];
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            // Find the digits[i]-th unused element
            int count = digits[i];
            for (int elem = 0; elem < n; elem++) {
                if (!used[elem]) {
                    if (count == 0) {
                        perm[i] = elem;
                        used[elem] = true;
                        break;
                    }
                    count--;
                }
            }
        }
        return perm;
    }

    /**
     * Converts a permutation of {@code [0, n-1]} to its 0-based lexicographic rank.
     *
     * <p>Algorithm: compute the Lehmer code of the permutation, then evaluate it in the factorial
     * number system.
     *
     * <ol>
     *   <li>For each position {@code i}, count how many elements to the right of {@code perm[i]}
     *       are smaller than {@code perm[i]} (this is the Lehmer code digit {@code d[i]}).
     *   <li>Rank = {@code Σ d[i] × (n-1-i)!}
     * </ol>
     *
     * <p>This method is O(n²) time and O(n) space.
     *
     * @param perm permutation to rank; must be a valid permutation of {@code [0, n-1]}
     * @return 0-based lexicographic rank of the permutation
     * @throws IllegalArgumentException if {@code perm} is null, empty, too long (length > 17), or
     *     is not a valid permutation of {@code [0, n-1]}
     */
    public static long permutationToRank(int[] perm) {
        if (perm == null) {
            throw new IllegalArgumentException("perm must not be null");
        }
        int n = perm.length;
        validateN(n);
        validatePermutation(perm, n);

        logFirstUse();

        long rankValue = 0L;
        boolean[] seen = new boolean[n];

        for (int i = 0; i < n; i++) {
            int element = perm[i];
            // Count how many elements smaller than perm[i] are still available
            int lehmerDigit = 0;
            for (int k = 0; k < element; k++) {
                if (!seen[k]) {
                    lehmerDigit++;
                }
            }
            seen[element] = true;
            rankValue += (long) lehmerDigit * FACTORIAL[n - 1 - i];
        }

        return rankValue;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void validateN(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1, got: " + n);
        }
        if (n > MAX_N) {
            throw new IllegalArgumentException(
                    "n must be at most "
                            + MAX_N
                            + " (17! is the largest factorial that fits in long), got: "
                            + n);
        }
    }

    private static void validatePermutation(int[] perm, int n) {
        boolean[] present = new boolean[n];
        for (int i = 0; i < n; i++) {
            int elem = perm[i];
            if (elem < 0 || elem >= n) {
                throw new IllegalArgumentException(
                        "perm["
                                + i
                                + "]="
                                + elem
                                + " is out of range [0, "
                                + (n - 1)
                                + "]"
                                + " for a permutation of length "
                                + n);
            }
            if (present[elem]) {
                throw new IllegalArgumentException(
                        "perm contains duplicate value "
                                + elem
                                + " — not a valid permutation of [0, "
                                + (n - 1)
                                + "]");
            }
            present[elem] = true;
        }
    }

    /**
     * Emits exactly one INFO log line per JVM (AC7). Uses double-checked volatile read to avoid
     * locking on the hot path after the first call.
     */
    private static void logFirstUse() {
        if (!firstUseLogged) {
            synchronized (LehmerCodec.class) {
                if (!firstUseLogged) {
                    LOG.info("LehmerCodec v{} initialized (de.vvwt.worker.codec)", VERSION);
                    firstUseLogged = true;
                }
            }
        }
    }
}
