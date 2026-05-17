// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import java.util.UUID;

/**
 * Leg 3 service: cancelable in-process slot optimization for N > {@code
 * tm.slotopt.exhaustive-max-n} when no dispatcher is configured or reachable (E27S02, DEC-49 D-3,
 * DEC-4 V1 amendment).
 *
 * <p>Provides cooperative cancellation via a {@link CancellationToken} checked between permutation
 * evaluations, with best-so-far result tracking (DEC-49 D-11a).
 *
 * <p>Per DEC-35 naming canon: this interface lives at the {@code de.vvwt.tm.slotopt} module root;
 * the implementation {@link
 * de.vvwt.tm.slotopt.internal.DefaultCancelableInProcessSlotOptimizationService} lives in {@code
 * de.vvwt.tm.slotopt.internal}.
 *
 * <h2>Admin-cancel contract</h2>
 *
 * <p>On cancel, the best permutation found so far is applied via {@link SlotResultApplicator} in
 * the same transactional scope as the REST call (AC-CANCEL-APPLIES-RESULT-IN-SAME-TX). If no
 * permutation has been evaluated yet at cancel time, trivial coordinates are assigned (lap 0,
 * sequential field numbers — analogous to the {@link FallbackSlotOptimizationClient} pattern).
 *
 * @see SlotOptimizationJobRegistry
 * @see JobHandle
 * @see CancellationToken
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-49.md">DEC-49 D-3, D-11</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 */
public interface CancelableInProcessSlotOptimizationService {

    /**
     * Runs the slot optimization for the given phase, checking {@code token} between permutations.
     *
     * <p>Returns the best result found: either the global optimum (natural completion) or the
     * best-so-far permutation (if {@code token.isCancelled()} returns {@code true} during the
     * loop).
     *
     * <p>After this method returns, slot coordinates have been applied to all matches in the phase
     * via {@link SlotResultApplicator}.
     *
     * @param phaseId the phase whose matches should receive slot assignments
     * @param tournamentId the tournament UUID, used to look up the active {@link JobHandle} for
     *     best-so-far tracking
     * @param token the cooperative cancellation flag; checked between permutations
     * @return the optimization result (best rank + score + cancellation flag)
     * @throws IllegalArgumentException if any argument is {@code null} or the phase does not exist
     * @throws IllegalStateException if no matches exist for the phase
     */
    OptimizationResult optimize(UUID phaseId, UUID tournamentId, CancellationToken token);
}
