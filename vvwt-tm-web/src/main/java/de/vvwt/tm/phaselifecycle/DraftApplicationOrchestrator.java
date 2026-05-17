// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import de.vvwt.tm.tournament.draft.DraftConfig;
import java.util.List;
import java.util.UUID;

/**
 * Entry-point for the apply-and-orchestrate workflow (E55S06, Option C, DEC-64 D-11).
 *
 * <p>This interface is declared in the {@code phaselifecycle} module-root per DEC-58
 * (universal-interface-mandate) and DEC-35 (naming canon). Default implementation: {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultDraftApplicationOrchestrator}.
 *
 * <h2>Option C architecture (DEC-64 D-11 + DEC-21 Modulith boundary)</h2>
 *
 * <p>The {@code tournament} module has {@code allowedDependencies = {"tenant"}} — it cannot import
 * from {@code phaselifecycle}. The {@code phaselifecycle} module has {@code allowedDependencies =
 * {"tournament", "slotopt", "tenant"}} — it CAN import from {@code tournament}. Option C exploits
 * this allowed direction:
 *
 * <pre>
 *   web (primary-adapter) → phaselifecycle → tournament
 * </pre>
 *
 * <p>The web controller (DEC-40 primary-adapter module) injects this interface and calls {@link
 * #applyDraft(UUID, DraftConfig)} instead of {@link de.vvwt.tm.tournament.DraftService#apply}
 * directly. This preserves DEC-21 {@code ApplicationModules.verify()} green.
 *
 * <h2>Transactional semantics</h2>
 *
 * <p>The implementation runs in a single {@code @Transactional} boundary:
 *
 * <ol>
 *   <li>Calls {@link de.vvwt.tm.tournament.DraftService#apply(UUID, DraftConfig)} (REQUIRED
 *       propagation — joins the outer TX). Returns the created phase UUIDs.
 *   <li>For each phase ID: inserts a PENDING {@code phase_lifecycle_job} row via {@link
 *       PhaseLifecycleJobRepository#enqueueJob(PhaseLifecycleJob)}.
 *   <li>Triggers an immediate drain hint via {@link JobDrainService#drainNext(UUID)} (best-effort —
 *       if the worker is idle, primes it; if busy, next poll picks up the rows).
 * </ol>
 *
 * <p>All three steps commit atomically. Failure at any step rolls back the entire TX per
 * AC-ERROR-HANDLING-APPLY-ROLLBACK.
 *
 * @see de.vvwt.tm.phaselifecycle.internal.DefaultDraftApplicationOrchestrator
 * @see de.vvwt.tm.tournament.DraftService
 * @see PhaseLifecycleJobRepository
 * @see JobDrainService
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-64.md">DEC-64 D-11</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 Modulith</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-58.md">DEC-58</a>
 * @since E55S06
 */
public interface DraftApplicationOrchestrator {

    /**
     * Applies the draft configuration for a tournament, enqueues phase lifecycle job rows, and
     * triggers an immediate worker drain.
     *
     * <p>Executes atomically in a single {@code @Transactional} boundary:
     *
     * <ol>
     *   <li>Delegates phase + avatar persistence to {@link
     *       de.vvwt.tm.tournament.DraftService#apply}.
     *   <li>Inserts one PENDING {@code phase_lifecycle_job} row per phase via {@link
     *       PhaseLifecycleJobRepository#enqueueJob(PhaseLifecycleJob)}.
     *   <li>Calls {@link JobDrainService#drainNext(UUID)} as a best-effort drain hint.
     * </ol>
     *
     * @param tournamentId the tournament for which the draft is applied
     * @param config the draft configuration (validated by caller)
     * @return the list of created phase UUIDs (forwarded from {@link
     *     de.vvwt.tm.tournament.DraftService#apply})
     * @throws de.vvwt.tm.tournament.exceptions.TournamentNotFoundException if the tournament does
     *     not exist
     * @throws de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException if the tournament is
     *     not in DRAFT status
     * @throws IllegalArgumentException if {@code tournamentId} or {@code config} is {@code null}
     */
    List<UUID> applyDraft(UUID tournamentId, DraftConfig config);
}
