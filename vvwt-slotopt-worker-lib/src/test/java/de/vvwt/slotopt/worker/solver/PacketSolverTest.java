// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link PacketSolver}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01) with fresh TDD tests under
 * DEC-22 Iron Law. All tests satisfy DEC-41 criterion (b) (compositional/bijection invariant) or
 * criterion (d) (named algebraic invariant quantified over a representative or exhaustive input
 * set).
 */
@DisplayName("PacketSolver — Spec-Anchored algebraic invariant tests (DEC-41 D-4 replacement)")
class PacketSolverTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Computes n! as a long (n in [0, 17]). */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }

    /**
     * Builds a minimal but non-trivial {@link CanonicalPhaseDef} for n rows. Avatar assignment: row
     * i activates avatar (i % (n/2)), with at least 1 avatar. Produces a valid, non-degenerate
     * phase usable across all n in [1, 17].
     */
    private static CanonicalPhaseDef buildSimplePhaseDef(int n) {
        List<List<Integer>> rows = new ArrayList<>();
        for (int row = 0; row < n; row++) {
            rows.add(List.of(row % Math.max(1, n / 2)));
        }
        int avatarCount = Math.max(1, n / 2);
        return new CanonicalPhaseDef(n, avatarCount, rows);
    }

    private static JobDef buildJobDef(int n) {
        return new JobDef(UUID.randomUUID(), n, buildSimplePhaseDef(n));
    }

    /**
     * Independent brute-force reference: exhaustively scores all permutations via the non-matrix
     * {@link VarietyScorer#score(int[], CanonicalPhaseDef)} overload — a code path independent from
     * PacketSolver's matrix-based kernel.
     */
    private static long bruteForceReference(JobDef jobDef) {
        final VarietyScorer scorer = new VarietyScorer();
        final CanonicalPhaseDef phaseDef = jobDef.canonicalPhaseDef();
        final int n = jobDef.n();
        final long nFactorial = factorial(n);
        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        for (long rank = 0L; rank < nFactorial; rank++) {
            int[] perm = LehmerCodec.rankToPermutation(rank, n);
            double score = scorer.score(perm, phaseDef);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }
        return bestRank;
    }

    // -------------------------------------------------------------------------
    // Criterion (d): result-shape invariant
    // Invariant: solvePacket returns a PacketResult with permutationsScored == (rankTo - rankFrom),
    //            bestRank in [rankFrom, rankTo-1], wallClockNanos >= 0
    // Quantified over three distinct (n, rankFrom, rankTo) shapes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: result-shape — permutationsScored == rankTo-rankFrom for all valid inputs"
                    + " (3 shapes)")
    void invariant_resultShape_permutationsScoredEqualsRangeSize() {
        // Shape 1: full range n=4
        int n1 = 4;
        long nF1 = factorial(n1);
        PacketResult r1 = PacketSolver.solvePacket(buildJobDef(n1), 0L, nF1);
        assertThat(r1.permutationsScored())
                .as(
                        "result-shape: permutationsScored must equal rankTo-rankFrom for full range"
                                + " n=%d",
                        n1)
                .isEqualTo(nF1);
        assertThat(r1.bestRank()).isBetween(0L, nF1 - 1L);
        assertThat(r1.wallClockNanos()).isGreaterThanOrEqualTo(0L);

        // Shape 2: subrange n=5
        int n2 = 5;
        PacketResult r2 = PacketSolver.solvePacket(buildJobDef(n2), 10L, 50L);
        assertThat(r2.permutationsScored())
                .as("result-shape: permutationsScored must equal 40 for subrange [10,50)")
                .isEqualTo(40L);
        assertThat(r2.bestRank()).isBetween(10L, 49L);

        // Shape 3: single-permutation packet
        int n3 = 3;
        PacketResult r3 = PacketSolver.solvePacket(buildJobDef(n3), 0L, 1L);
        assertThat(r3.permutationsScored())
                .as("result-shape: permutationsScored must equal 1 for single-permutation packet")
                .isEqualTo(1L);
        assertThat(r3.bestRank()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Criterion (d): determinism invariant
    // Invariant: solvePacket(jobDef, rankFrom, rankTo) is deterministic — identical invocations
    //            return bit-identical bestRank and bestScore
    // Quantified over full-range (n=6) and subrange (n=5, [10,50))
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: determinism — identical invocations return bit-identical results (full"
                    + " range n=6)")
    void invariant_determinism_fullRange() {
        JobDef jobDef = buildJobDef(6);
        long rankTo = factorial(6);
        PacketResult first = PacketSolver.solvePacket(jobDef, 0L, rankTo);
        PacketResult second = PacketSolver.solvePacket(jobDef, 0L, rankTo);
        assertThat(first.bestRank())
                .as("Determinism invariant: bestRank must be identical across two invocations")
                .isEqualTo(second.bestRank());
        assertThat(Double.doubleToLongBits(first.bestScore()))
                .as(
                        "Determinism invariant: bestScore bits must be identical across two"
                                + " invocations")
                .isEqualTo(Double.doubleToLongBits(second.bestScore()));
    }

    @Test
    @DisplayName(
            "Invariant: determinism — identical subrange invocations return bit-identical results")
    void invariant_determinism_subrange() {
        JobDef jobDef = buildJobDef(5);
        PacketResult first = PacketSolver.solvePacket(jobDef, 10L, 50L);
        PacketResult second = PacketSolver.solvePacket(jobDef, 10L, 50L);
        assertThat(first.bestRank())
                .as("Determinism invariant: bestRank must be identical for subrange invocations")
                .isEqualTo(second.bestRank());
        assertThat(Double.doubleToLongBits(first.bestScore()))
                .as(
                        "Determinism invariant: bestScore bits must be identical for subrange"
                                + " invocations")
                .isEqualTo(Double.doubleToLongBits(second.bestScore()));
    }

    // -------------------------------------------------------------------------
    // Criterion (d): tie-break invariant (named algebraic invariant)
    // Invariant: when all permutations score identically, the result is the minimum rank
    //            (tie-break rule: lower rank wins)
    // Quantified over n=2 (2 perms) and n=3 (6 perms) with fully-tied phase constructions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: tie-break — minimum rank is returned when all permutations are score-equal"
                    + " (n=2)")
    void invariant_tieBreak_minimumRankWins_n2() {
        // Phase: single avatar active in all rows → every permutation has identical score
        int n = 2;
        List<List<Integer>> rows = List.of(List.of(0), List.of(0));
        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, phaseDef);
        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(n));
        assertThat(result.bestRank())
                .as(
                        "Tie-break invariant: minimum rank 0 must win when all %d permutations are"
                                + " score-equal",
                        factorial(n))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName(
            "Invariant: tie-break — minimum rank is returned when all permutations are score-equal"
                    + " (n=3)")
    void invariant_tieBreak_minimumRankWins_n3() {
        int n = 3;
        List<List<Integer>> rows = List.of(List.of(0), List.of(0), List.of(0));
        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, phaseDef);
        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(n));
        assertThat(result.bestRank())
                .as(
                        "Tie-break invariant: minimum rank 0 must win when all %d permutations are"
                                + " score-equal",
                        factorial(n))
                .isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Criterion (b): correctness invariant (independent-path equivalence)
    // Invariant: solvePacket(jobDef, 0, n!) == bruteForceReference(jobDef)
    //   where bruteForce uses an independent code path (non-matrix scorer)
    // Quantified over n in [3, 6] (exhaustive small-n verification)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: correctness — solvePacket matches independent brute-force reference for n"
                    + " in [3,6]")
    void invariant_correctness_matchesBruteForceReference_nThreeToNSix() {
        for (int n = 3; n <= 6; n++) {
            JobDef jobDef = buildJobDef(n);
            long nFactorial = factorial(n);
            PacketResult kernelResult = PacketSolver.solvePacket(jobDef, 0L, nFactorial);
            long referenceRank = bruteForceReference(jobDef);
            assertThat(kernelResult.bestRank())
                    .as(
                            "Correctness invariant: solvePacket must match brute-force reference"
                                    + " for n=%d",
                            n)
                    .isEqualTo(referenceRank);
        }
    }

    // -------------------------------------------------------------------------
    // Criterion (b): subrange-composition invariant
    // Invariant: min over {solvePacket(jobDef, 0, n!/2), solvePacket(jobDef, n!/2, n!)}
    //   (best of two halves, with tie-break by lowest rank) == solvePacket(jobDef, 0, n!)
    // Quantified over n=5 (120 permutations, split at 60)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: subrange-composition — best of two halves equals full-range result (n=5)")
    void invariant_subrangeComposition_bestOfHalvesEqualsFullRange() {
        int n = 5;
        JobDef jobDef = buildJobDef(n);
        long nFactorial = factorial(n);
        long midpoint = nFactorial / 2;

        PacketResult fullRange = PacketSolver.solvePacket(jobDef, 0L, nFactorial);
        PacketResult firstHalf = PacketSolver.solvePacket(jobDef, 0L, midpoint);
        PacketResult secondHalf = PacketSolver.solvePacket(jobDef, midpoint, nFactorial);

        // Apply tie-break rule (lower rank wins on equal score)
        long combinedBestRank;
        if (firstHalf.bestScore() < secondHalf.bestScore()) {
            combinedBestRank = firstHalf.bestRank();
        } else if (secondHalf.bestScore() < firstHalf.bestScore()) {
            combinedBestRank = secondHalf.bestRank();
        } else {
            combinedBestRank = firstHalf.bestRank(); // tie: first half has lower ranks
        }

        assertThat(combinedBestRank)
                .as(
                        "Subrange-composition invariant: best of two halves must equal full-range"
                                + " bestRank for n=%d",
                        n)
                .isEqualTo(fullRange.bestRank());
    }

    // -------------------------------------------------------------------------
    // Criterion (d): guard-clause invariant (PacketSolver inputs)
    // Invariant: every out-of-contract input is rejected with IAE
    // Quantified over the complete guard-clause boundary of PacketSolver.solvePacket
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Invariant: guard-clause — null jobDef throws IAE")
    void invariant_guardClause_nullJobDef() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(null, 0L, 6L));
    }

    @Test
    @DisplayName("Invariant: guard-clause — negative rankFrom throws IAE")
    void invariant_guardClause_negativeRankFrom() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(buildJobDef(4), -1L, 5L));
    }

    @Test
    @DisplayName("Invariant: guard-clause — rankTo > n! throws IAE")
    void invariant_guardClause_rankToExceedsNFactorial() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(buildJobDef(3), 0L, factorial(3) + 1L));
    }

    @Test
    @DisplayName("Invariant: guard-clause — rankFrom == rankTo throws IAE (empty range)")
    void invariant_guardClause_rankFromEqualsRankTo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(buildJobDef(3), 3L, 3L));
    }

    @Test
    @DisplayName("Invariant: guard-clause — rankFrom > rankTo throws IAE (inverted range)")
    void invariant_guardClause_rankFromGreaterThanRankTo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(buildJobDef(3), 4L, 2L));
    }

    @Test
    @DisplayName(
            "Invariant: guard-clause — negative avatar index in phaseDef throws IAE (malformed"
                    + " input)")
    void invariant_guardClause_negativeAvatarIndex_inPhaseDef() {
        int n = 3;
        List<List<Integer>> rows = List.of(List.of(-1), List.of(0), List.of(0));
        CanonicalPhaseDef malformed = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, malformed);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, 0L, factorial(n)));
    }

    // -------------------------------------------------------------------------
    // Criterion (d): guard-clause invariant (JobDef constructor)
    // Invariant: every out-of-contract JobDef construction is rejected with IAE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Invariant: guard-clause — null jobId in JobDef throws IAE")
    void invariant_guardClause_jobDef_nullJobId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(null, 3, buildSimplePhaseDef(3)));
    }

    @Test
    @DisplayName("Invariant: guard-clause — n=0 in JobDef throws IAE (lower n boundary)")
    void invariant_guardClause_jobDef_nZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 0, buildSimplePhaseDef(1)));
    }

    @Test
    @DisplayName("Invariant: guard-clause — n=18 in JobDef throws IAE (upper n boundary)")
    void invariant_guardClause_jobDef_nEighteen() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 18, buildSimplePhaseDef(1)));
    }

    @Test
    @DisplayName("Invariant: guard-clause — null phaseDef in JobDef throws IAE")
    void invariant_guardClause_jobDef_nullPhaseDef() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 3, null));
    }

    // -------------------------------------------------------------------------
    // Criterion (d): wallClockNanos non-negativity invariant
    // Invariant: wallClockNanos is always >= 0 (timing measurement is non-negative)
    // Quantified over three distinct packet shapes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Invariant: wallClockNanos >= 0 for full-range, subrange, and single-permutation"
                    + " packets")
    void invariant_wallClockNanos_isNonNegative_allShapes() {
        // Full range
        assertThat(PacketSolver.solvePacket(buildJobDef(4), 0L, factorial(4)).wallClockNanos())
                .as("wallClockNanos invariant: must be >= 0 for full-range packet")
                .isGreaterThanOrEqualTo(0L);
        // Subrange
        assertThat(PacketSolver.solvePacket(buildJobDef(5), 5L, 25L).wallClockNanos())
                .as("wallClockNanos invariant: must be >= 0 for subrange packet")
                .isGreaterThanOrEqualTo(0L);
        // Single permutation
        assertThat(PacketSolver.solvePacket(buildJobDef(3), 0L, 1L).wallClockNanos())
                .as("wallClockNanos invariant: must be >= 0 for single-permutation packet")
                .isGreaterThanOrEqualTo(0L);
    }
}
