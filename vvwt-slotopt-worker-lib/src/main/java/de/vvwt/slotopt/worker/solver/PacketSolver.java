package de.vvwt.slotopt.worker.solver;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic compute kernel for slot-optimization.
 *
 * <p>Composes {@link LehmerCodec} and {@link VarietyScorer} into a single closed-form function:
 * {@link #solvePacket(JobDef, long, long) solvePacket(jobDef, rankFrom, rankTo) → PacketResult}.
 *
 * <h2>Determinism (AC2 — Brief D-3, Q-2)</h2>
 *
 * <p>For identical inputs on any JVM, on any platform supported by Phase 1 (Linux/macOS/Windows ×
 * x86_64/aarch64), this method returns bit-identical {@code bestRank} and {@code bestScore}. The
 * guarantee holds because:
 *
 * <ul>
 *   <li>Rank-to-permutation conversion is pure integer arithmetic ({@link LehmerCodec}).
 *   <li>Scoring uses only {@code *}, {@code +}, {@code /} on values promoted from {@code int} to
 *       {@code double} — IEEE 754 double-precision arithmetic is deterministic for this operation
 *       set on all supported platforms ({@link VarietyScorer#scoreWithMatrix}).
 *   <li>The active-matrix is rebuilt deterministically from the canonical phase definition.
 *   <li>No random, thread-local, or platform-dependent state enters the hot path.
 * </ul>
 *
 * <h2>Tie-break (AC3 — Brief D-3)</h2>
 *
 * <p>When two permutations have equal {@code bestScore}, the permutation with the <strong>lowest
 * Lehmer rank</strong> wins. The implementation achieves this by iterating ranks in ascending order
 * and updating the best only on a <em>strict improvement</em> ({@code newScore < bestScore}), never
 * on equality. The first (lowest-rank) permutation with a given score is therefore always retained.
 *
 * <h2>Security (AC6)</h2>
 *
 * <p>No I/O, no logging, and no system-time sampling occur inside the hot loop. {@code
 * System.nanoTime()} is called exactly twice: immediately before and immediately after the hot
 * loop. This class is stateless and therefore unconditionally thread-safe.
 *
 * <h2>Abuse resistance (AC7)</h2>
 *
 * <p>All inputs are validated at the packet boundary before the hot loop is entered. A malformed or
 * untrusted {@link CanonicalPhaseDef} (e.g., negative avatar indices, row-count mismatch) is caught
 * at matrix-build time and throws {@link IllegalArgumentException} before any scoring work begins.
 *
 * <h2>Observability (AC8)</h2>
 *
 * <p>Exactly one {@code DEBUG} line is emitted per solve call (after the loop):
 *
 * <pre>
 * solved packet [{rankFrom}, {rankTo}) of job {jobId}: best=({rank}, {score}), perms={n}, wall={ms}ms
 * </pre>
 *
 * <h2>Story</h2>
 *
 * <p>Implements story E01S03 (AC1–AC9) in {@code vvwt-worker-lib} per DEC-11.
 */
public final class PacketSolver {

    private static final Logger LOG = LoggerFactory.getLogger(PacketSolver.class);

    /** Private constructor — stateless utility class. */
    private PacketSolver() {
        throw new UnsupportedOperationException("PacketSolver is a utility class");
    }

    /**
     * Scores every permutation in the rank interval {@code [rankFrom, rankTo)} and returns the best
     * one — the permutation with the lowest variety score, with ties broken by lowest rank.
     *
     * <p>The method is deterministic: identical inputs always produce identical outputs,
     * bit-for-bit, on any JVM or platform (see class-level Javadoc for the proof sketch).
     *
     * @param jobDef the job descriptor; must not be {@code null}; {@code jobDef.n} must be in
     *     [{@value de.vvwt.worker.types.JobDef#MIN_N}, {@value de.vvwt.worker.types.JobDef#MAX_N}]
     * @param rankFrom inclusive lower bound of the rank interval; must be ≥ 0
     * @param rankTo exclusive upper bound of the rank interval; must be ≤ {@code jobDef.n}! and
     *     strictly greater than {@code rankFrom}
     * @return the best {@link PacketResult} for this packet
     * @throws IllegalArgumentException if any argument is invalid (null jobDef, n out of range,
     *     rankFrom < 0, rankTo > n!, rankFrom >= rankTo, or malformed phaseDef)
     */
    public static PacketResult solvePacket(JobDef jobDef, long rankFrom, long rankTo) {
        validateArguments(jobDef, rankFrom, rankTo);

        final int n = jobDef.n();
        final CanonicalPhaseDef phaseDef = jobDef.canonicalPhaseDef();

        // Build the active-matrix once per call (amortized over all ranks in the packet).
        // This is the only allocation in the outer scope; the hot loop itself is allocation-free.
        // Wrapped to catch any malformed phaseDef data (AC7 — abuse resistance boundary).
        final VarietyScorer scorer = new VarietyScorer();
        final boolean[][] activeMatrix;
        try {
            activeMatrix =
                    scorer.buildActiveMatrix(
                            phaseDef.rows(), phaseDef.rowCount(), phaseDef.avatarCount());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "PacketSolver rejected packet for job "
                            + jobDef.jobId()
                            + ": malformed canonicalPhaseDef — "
                            + e.getMessage(),
                    e);
        }

        final int rowCount = phaseDef.rowCount();
        final int avatarCount = phaseDef.avatarCount();

        // Record start time at the packet boundary only (AC6 — never mid-loop).
        final long startNanos = System.nanoTime();

        // --- Hot loop — no I/O, no logging, no System.nanoTime() inside ---
        long bestRank = rankFrom;
        double bestScore = Double.MAX_VALUE;

        for (long rank = rankFrom; rank < rankTo; rank++) {
            final int[] permutation = LehmerCodec.rankToPermutation(rank, n);
            final double score =
                    scorer.scoreWithMatrix(permutation, rowCount, avatarCount, activeMatrix);

            // Strict improvement only — equal scores keep the first (lowest) rank (AC3).
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }
        // --- End hot loop ---

        final long endNanos = System.nanoTime();
        final long wallClockNanos = endNanos - startNanos;
        final long permutationsScored = rankTo - rankFrom;

        // AC8: exactly one DEBUG line per solve call, after the loop.
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "solved packet [{}, {}) of job {}: best=({}, {}), perms={}, wall={}ms",
                    rankFrom,
                    rankTo,
                    jobDef.jobId(),
                    bestRank,
                    bestScore,
                    permutationsScored,
                    wallClockNanos / 1_000_000L);
        }

        return new PacketResult(bestRank, bestScore, permutationsScored, wallClockNanos);
    }

    // -------------------------------------------------------------------------
    // Input validation (AC5 + AC7)
    // -------------------------------------------------------------------------

    /**
     * Computes {@code n!} for {@code n} in [1, 17]. All values fit in {@code long}. Computed
     * locally to avoid depending on the package-private {@code LehmerCodec.FACTORIAL} array.
     */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    private static void validateArguments(JobDef jobDef, long rankFrom, long rankTo) {
        if (jobDef == null) {
            throw new IllegalArgumentException("jobDef must not be null");
        }
        // Note: jobDef.n() and jobDef.canonicalPhaseDef() are already validated by JobDef's
        // canonical constructor, so we only need to check rank bounds here.
        final int n = jobDef.n();
        final long nFactorial = factorial(n);

        if (rankFrom < 0) {
            throw new IllegalArgumentException("rankFrom must be >= 0, got: " + rankFrom);
        }
        if (rankTo > nFactorial) {
            throw new IllegalArgumentException(
                    "rankTo=" + rankTo + " exceeds n!=" + nFactorial + " for n=" + n);
        }
        if (rankFrom >= rankTo) {
            throw new IllegalArgumentException(
                    "rankFrom="
                            + rankFrom
                            + " must be strictly less than rankTo="
                            + rankTo
                            + " (empty packet is not allowed)");
        }
    }
}
