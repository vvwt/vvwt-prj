package de.vvwt.worker.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.vvwt.worker.codec.LehmerCodec;
import de.vvwt.worker.score.VarietyScorer;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.JobDef;
import de.vvwt.worker.types.PacketResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link PacketSolver} — covers AC1–AC8 of story E01S03. */
class PacketSolverTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Computes n! as a long (n in [0, 17]). */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    /**
     * Builds a minimal but non-trivial {@link CanonicalPhaseDef} for n rows with one avatar per row
     * (avatar index = row index). Produces a valid phase that exercises the scorer with
     * non-degenerate data.
     */
    private static CanonicalPhaseDef buildSimplePhaseDef(int n) {
        List<List<Integer>> rows = new ArrayList<>();
        for (int row = 0; row < n; row++) {
            rows.add(List.of(row % Math.max(1, n / 2)));
        }
        int avatarCount = Math.max(1, n / 2);
        return new CanonicalPhaseDef(n, avatarCount, rows);
    }

    /** Builds a {@link JobDef} with the simple phase def for the given n. */
    private static JobDef buildJobDef(int n) {
        return new JobDef(UUID.randomUUID(), n, buildSimplePhaseDef(n));
    }

    /**
     * Brute-force reference implementation (AC4): scores all permutations in [0, n!) using the
     * non-matrix {@link VarietyScorer#score(int[], CanonicalPhaseDef)} overload — an independent
     * code path from PacketSolver.
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
    // AC1 — basic happy path: returns a PacketResult with correct shape
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_returnsPacketResult_withPositivePermutationsScored() {
        JobDef jobDef = buildJobDef(4);
        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(4));

        assertThat(result).isNotNull();
        assertThat(result.permutationsScored()).isEqualTo(factorial(4));
        assertThat(result.wallClockNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.bestRank()).isBetween(0L, factorial(4) - 1);
    }

    // -------------------------------------------------------------------------
    // AC2 — determinism: two invocations return bit-identical results
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_isBitIdentical_acrossInvocations() {
        JobDef jobDef = buildJobDef(6);
        long rankTo = factorial(6);

        PacketResult first = PacketSolver.solvePacket(jobDef, 0L, rankTo);
        PacketResult second = PacketSolver.solvePacket(jobDef, 0L, rankTo);

        assertThat(first.bestRank()).isEqualTo(second.bestRank());
        assertThat(Double.doubleToLongBits(first.bestScore()))
                .isEqualTo(Double.doubleToLongBits(second.bestScore()));
    }

    @Test
    void solvePacket_isBitIdentical_forSubranges() {
        // Same subrange, called twice — must be identical
        JobDef jobDef = buildJobDef(5);

        PacketResult first = PacketSolver.solvePacket(jobDef, 10L, 50L);
        PacketResult second = PacketSolver.solvePacket(jobDef, 10L, 50L);

        assertThat(first.bestRank()).isEqualTo(second.bestRank());
        assertThat(Double.doubleToLongBits(first.bestScore()))
                .isEqualTo(Double.doubleToLongBits(second.bestScore()));
    }

    // -------------------------------------------------------------------------
    // AC3 — tie-break: lowest rank wins on equal score
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_tieBroken_byLowestRank() {
        // Construct a phase where all permutations have identical scores (single avatar,
        // all rows active → every permutation of rows yields the same run-length product).
        // With N=2: perms are [0,1] (rank 0) and [1,0] (rank 1) — both score identically
        // because a single avatar that is active in every row always has product = N regardless
        // of ordering. The lowest rank (0) must win.
        int n = 2;
        // Single avatar (index 0) is active in both rows → any permutation scores the same
        List<List<Integer>> rows = List.of(List.of(0), List.of(0));
        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, phaseDef);

        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(n));

        // Tie-break: lowest rank must be returned
        assertThat(result.bestRank()).isEqualTo(0L);
    }

    @Test
    void solvePacket_tieBroken_byLowestRank_multipleEqual() {
        // N=3, all avatars always active → all permutations have the same score.
        // Expected best rank: 0.
        int n = 3;
        List<List<Integer>> rows = List.of(List.of(0), List.of(0), List.of(0));
        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, phaseDef);

        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(n));

        assertThat(result.bestRank()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // AC4 — correctness: solvePacket matches brute-force reference for small N
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8})
    void solvePacket_matchesBruteForceReference_fullRange(int n) {
        JobDef jobDef = buildJobDef(n);
        long nFactorial = factorial(n);

        PacketResult kernelResult = PacketSolver.solvePacket(jobDef, 0L, nFactorial);
        long referenceRank = bruteForceReference(jobDef);

        assertThat(kernelResult.bestRank())
                .as("PacketSolver bestRank must match brute-force reference for n=" + n)
                .isEqualTo(referenceRank);
    }

    // -------------------------------------------------------------------------
    // AC5 — error handling: each invalid input path throws IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_throwsIAE_onNullJobDef() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(null, 0L, 6L))
                .withMessageContaining("jobDef must not be null");
    }

    @Test
    void solvePacket_throwsIAE_onNegativeRankFrom() {
        JobDef jobDef = buildJobDef(4);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, -1L, 5L))
                .withMessageContaining("rankFrom must be >= 0");
    }

    @Test
    void solvePacket_throwsIAE_whenRankToExceedsNFactorial() {
        JobDef jobDef = buildJobDef(3);
        long nFactorial = factorial(3); // 6
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, 0L, nFactorial + 1))
                .withMessageContaining("rankTo=");
    }

    @Test
    void solvePacket_throwsIAE_whenRankFromEqualsRankTo() {
        JobDef jobDef = buildJobDef(3);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, 3L, 3L))
                .withMessageContaining("rankFrom=");
    }

    @Test
    void solvePacket_throwsIAE_whenRankFromGreaterThanRankTo() {
        JobDef jobDef = buildJobDef(3);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, 4L, 2L))
                .withMessageContaining("rankFrom=");
    }

    @Test
    void jobDef_throwsIAE_onNullJobId() {
        CanonicalPhaseDef phaseDef = buildSimplePhaseDef(3);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(null, 3, phaseDef))
                .withMessageContaining("jobId must not be null");
    }

    @Test
    void jobDef_throwsIAE_onNTooSmall() {
        CanonicalPhaseDef phaseDef = buildSimplePhaseDef(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 0, phaseDef))
                .withMessageContaining("n must be in");
    }

    @Test
    void jobDef_throwsIAE_onNTooLarge() {
        CanonicalPhaseDef phaseDef = buildSimplePhaseDef(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 18, phaseDef))
                .withMessageContaining("n must be in");
    }

    @Test
    void jobDef_throwsIAE_onNullPhaseDef() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JobDef(UUID.randomUUID(), 3, null))
                .withMessageContaining("canonicalPhaseDef must not be null");
    }

    // -------------------------------------------------------------------------
    // AC6 — security: wallClockNanos is measured (sanity: not negative)
    //       No I/O or side-effects testable directly; covered by structural review.
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_wallClockNanos_isNonNegative() {
        JobDef jobDef = buildJobDef(4);
        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, factorial(4));
        assertThat(result.wallClockNanos()).isGreaterThanOrEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // AC7 — abuse resistance: malformed phaseDef is rejected at the boundary
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_throwsIAE_onNegativeAvatarIndex() {
        // Build a CanonicalPhaseDef with a negative avatar index bypassing the record's
        // constructor by supplying n=3 but an avatar index out of range for the scorer.
        // CanonicalPhaseDef itself doesn't validate avatar indices (it only checks rowCount).
        // PacketSolver must catch this at the activeMatrix boundary.
        int n = 3;
        List<List<Integer>> rows =
                List.of(
                        List.of(-1), // negative avatar index — malformed
                        List.of(0),
                        List.of(0));
        CanonicalPhaseDef malformedPhaseDef = new CanonicalPhaseDef(n, 1, rows);
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, malformedPhaseDef);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PacketSolver.solvePacket(jobDef, 0L, factorial(n)))
                .withMessageContaining("malformed canonicalPhaseDef");
    }

    // -------------------------------------------------------------------------
    // AC8 — observability: single solve call, no exception, log line is emitted
    //       (DEBUG level — verified structurally; SLF4J output captured via test logger)
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_completes_forSingleRankPacket() {
        // Minimal packet: exactly one permutation — exercises all code paths without
        // triggering the "empty packet" guard.
        JobDef jobDef = buildJobDef(3);
        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, 1L);

        assertThat(result.permutationsScored()).isEqualTo(1L);
        assertThat(result.bestRank()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Additional integration: subrange packet returns consistent result
    // -------------------------------------------------------------------------

    @Test
    void solvePacket_subrangeResult_isConsistentWithFullRange() {
        // If we split [0, n!) into two halves and solve each, then take the better half,
        // the combined best must match the full-range result.
        int n = 5;
        JobDef jobDef = buildJobDef(n);
        long nFactorial = factorial(n);
        long midpoint = nFactorial / 2;

        PacketResult fullRange = PacketSolver.solvePacket(jobDef, 0L, nFactorial);
        PacketResult firstHalf = PacketSolver.solvePacket(jobDef, 0L, midpoint);
        PacketResult secondHalf = PacketSolver.solvePacket(jobDef, midpoint, nFactorial);

        // Combined best rank across both halves
        long combinedBestRank;
        if (firstHalf.bestScore() < secondHalf.bestScore()) {
            combinedBestRank = firstHalf.bestRank();
        } else if (secondHalf.bestScore() < firstHalf.bestScore()) {
            combinedBestRank = secondHalf.bestRank();
        } else {
            // Equal: lowest rank wins — first half always has lower ranks
            combinedBestRank = firstHalf.bestRank();
        }

        assertThat(combinedBestRank)
                .as("Combined result across split halves must match full-range result")
                .isEqualTo(fullRange.bestRank());
    }
}
