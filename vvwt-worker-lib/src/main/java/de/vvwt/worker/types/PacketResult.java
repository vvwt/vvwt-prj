package de.vvwt.worker.types;

/**
 * The result of a solve-packet operation, produced by {@code PacketSolver.solvePacket()}.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #bestRank} — 0-based lexicographic rank of the permutation with the best
 *       (lowest) variety score in the packet. On a tie, the lowest rank wins (per Brief D-3).
 *   <li>{@link #bestScore} — the variety score of the best permutation (lower is better).
 *       Bit-identical across platforms when the same packet is solved (determinism invariant).
 *   <li>{@link #permutationsScored} — the number of permutations evaluated, i.e.
 *       {@code rankTo - rankFrom}.
 *   <li>{@link #wallClockNanos} — elapsed wall-clock time in nanoseconds from the start of the
 *       hot loop to the end, measured via {@code System.nanoTime()} at packet boundaries only.
 * </ul>
 *
 * <p>Instances are produced exclusively by {@code PacketSolver} and are immutable.
 *
 * @param bestRank           0-based rank of the best permutation found in [rankFrom, rankTo)
 * @param bestScore          variety score of the best permutation (lower = better variety)
 * @param permutationsScored number of permutations evaluated ({@code rankTo - rankFrom})
 * @param wallClockNanos     elapsed wall time in nanoseconds for the solve call
 */
public record PacketResult(long bestRank, double bestScore, long permutationsScored, long wallClockNanos) {
}
