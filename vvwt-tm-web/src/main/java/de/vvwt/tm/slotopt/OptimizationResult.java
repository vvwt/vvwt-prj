// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

/**
 * Value object representing the result of a slot-optimization computation — either completed
 * naturally or cancelled with the best permutation found so far (E27S02, AC-BEST-SO-FAR semantics,
 * DEC-49 D-11a).
 *
 * <p>When the computation finishes naturally, {@link #cancelled()} is {@code false} and {@link
 * #bestRank()} is the globally optimal permutation rank. When the computation is cancelled, {@link
 * #cancelled()} is {@code true} and {@link #bestRank()} carries the best rank found up to the
 * cancellation point.
 *
 * @param bestRank the 0-based Lehmer rank of the best permutation found (lowest variety score)
 * @param bestScore the variety score of the best permutation (lower is better)
 * @param cancelled {@code true} if this result was produced by a cancel, {@code false} if
 *     computation completed naturally
 * @see CancelableInProcessSlotOptimizationService
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-11a</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 */
public record OptimizationResult(long bestRank, double bestScore, boolean cancelled) {

    /**
     * Factory: natural-completion result.
     *
     * @param bestRank the globally optimal rank
     * @param bestScore the variety score
     * @return natural-completion result
     */
    public static OptimizationResult completed(long bestRank, double bestScore) {
        return new OptimizationResult(bestRank, bestScore, false);
    }

    /**
     * Factory: cancelled result (best-so-far).
     *
     * @param bestRank the best rank found before cancellation
     * @param bestScore the variety score of the best rank found
     * @return cancelled result
     */
    public static OptimizationResult cancelled(long bestRank, double bestScore) {
        return new OptimizationResult(bestRank, bestScore, true);
    }
}
