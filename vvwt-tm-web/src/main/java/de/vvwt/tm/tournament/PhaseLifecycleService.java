package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.Phase.PhaseStatus;
import java.util.UUID;

/**
 * Public port for phase lifecycle status transitions (DEC-35, E48S06, E51S05).
 *
 * <p>Drives each phase through its status lifecycle:
 *
 * <ul>
 *   <li>PENDING → PREPARED via {@link #transition(UUID, PhaseStatus, String)} with verb {@code
 *       "match-gen-done"} (E51S05)
 *   <li>PREPARED → ASSIGNED via {@link #transition(UUID, PhaseStatus, String)} with verb {@code
 *       "assign"} (E51S05 / E51S06)
 *   <li>ASSIGNED → ACTIVE via {@link #transition(UUID, PhaseStatus, String)} with verb {@code
 *       "start"} — gated by activation-guard {@code !tournament.optimize OR phase.optimized}
 *       (DEC-55 D-6, E51S05)
 *   <li>ACTIVE → COMPLETED via {@link #transition(UUID, PhaseStatus, String)} with verb {@code
 *       "complete"} or {@code "force-complete"} (E51S05)
 * </ul>
 *
 * <p>All methods acquire a per-tournament pessimistic DB row-lock as their first read (DEC-37
 * Clause B). This serialises concurrent lifecycle transitions on the same tournament aggregate
 * root.
 *
 * <p>The sole implementation is {@link de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService}
 * in the {@code tournament.internal} package per DEC-35.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService
 * @see <a href="DEC-35">DEC-35 — package layout: interface in public package</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB
 *     row-lock</a>
 * @see <a href="DEC-55">DEC-55 D-4 + D-6 — ASSIGNED status + transition-table +
 *     activation-guard</a>
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle Service</a>
 * @see <a href="E51S05">E51S05 — transition-table + activation-guard implementation</a>
 */
public interface PhaseLifecycleService {

    /**
     * Transitions the phase to the given {@code target} status using the specified {@code verb},
     * validated against the single-source-of-truth transition table (DEC-55 D-4, E51S05).
     *
     * <p>The allowed transitions are:
     *
     * <pre>
     * PENDING    → PREPARED   "match-gen-done"
     * PREPARED   → ASSIGNED   "assign"
     * ASSIGNED   → ASSIGNED   "re-assign"  (idempotent self-loop)
     * ASSIGNED   → ACTIVE     "start"      (activation-guard: !tournament.optimize OR phase.optimized)
     * ACTIVE     → COMPLETED  "complete"
     * ACTIVE     → COMPLETED  "force-complete"
     * </pre>
     *
     * <p>Any other {@code (source, target, verb)} triple throws {@link IllegalStateException} with
     * a message naming source status, target status, and verb.
     *
     * <p>The {@code ASSIGNED → ACTIVE "start"} transition additionally enforces the
     * activation-guard: {@code !tournament.optimize OR phase.optimized}. If the guard fails, throws
     * {@link de.vvwt.tm.tournament.exceptions.ConflictException} (HTTP 409) with an
     * operator-actionable message naming the phase UUID and the unmet condition.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read. Publishes
     * {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} on success.
     *
     * @param phaseId the phase UUID
     * @param target the target {@link PhaseStatus}
     * @param verb the action verb that discriminates edges with identical source+target (e.g.,
     *     {@code "complete"} vs {@code "force-complete"} for ACTIVE→COMPLETED)
     * @return the updated phase with the new status persisted
     * @throws IllegalArgumentException if {@code verb} is {@code null}, or if no phase with the
     *     given id exists (before any guard or table lookup)
     * @throws IllegalStateException if the {@code (source, target, verb)} triple is not in the
     *     allowed transitions table
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the activation-guard for
     *     ASSIGNED→ACTIVE fails (HTTP 409)
     */
    Phase transition(UUID phaseId, PhaseStatus target, String verb);

    /**
     * Transitions the phase from {@code PENDING} to {@code PREPARED} (E48S17 / E51S06 rollback of
     * E48S21).
     *
     * <p>Pure status flip only. Avatar persistence (E51S02 DraftConfig-Apply) and match generation
     * (E51S03 background job) happen in separate pipeline steps before this is called. This method
     * does not delegate to {@code commitTransition} — that responsibility was removed by E51S06
     * (DEC-55 D-10 rollback of E48S21).
     *
     * <p>Idempotent: if the phase is already {@code PREPARED}, returns the phase unchanged without
     * publishing an event. If the phase is in any other state, throws {@link
     * de.vvwt.tm.tournament.exceptions.ConflictException}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * @param phaseId the phase UUID
     * @return the updated phase with {@code status = "PREPARED"} (or unchanged if already PREPARED)
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code PENDING} and not {@code PREPARED}
     * @throws IllegalArgumentException if no phase with the given id exists
     */
    Phase prepare(UUID phaseId);

    /**
     * Transitions the phase from {@code PREPARED} to {@code ACTIVE} (E48S17 refactor).
     *
     * <p>Requires the phase to be in {@code PREPARED} status. Additionally, if the phase has a
     * predecessor (sequenceNumber &gt; 1), the predecessor must be in {@code COMPLETED} status.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read. Publishes
     * {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} on success.
     *
     * @param phaseId the phase UUID
     * @return the updated phase with {@code status = "ACTIVE"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code PREPARED}, or if the predecessor phase is not {@code COMPLETED}
     * @throws IllegalArgumentException if no phase with the given id exists
     */
    Phase start(UUID phaseId);

    /**
     * Transitions the phase from {@code ACTIVE} to {@code COMPLETED}.
     *
     * <p>This transition is only permitted when ALL matches in the phase are in a terminal state
     * ({@code FINISHED_WINNER1}, {@code FINISHED_WINNER2}, {@code FINISHED_STANDOFF}). If any
     * unfinished matches exist, a {@link de.vvwt.tm.tournament.exceptions.ConflictException} is
     * thrown with an operator-actionable message (HTTP 409).
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read. Publishes
     * {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} on success.
     *
     * @param phaseId the phase UUID
     * @return the updated phase with {@code status = "COMPLETED"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code ACTIVE}, or if unfinished matches prevent completion
     * @throws IllegalArgumentException if no phase with the given id exists
     */
    Phase complete(UUID phaseId);

    /**
     * Force-completes the phase: transitions {@code ACTIVE} to {@code COMPLETED} and voids all
     * unfinished matches (sets their state to {@code CANCELED(-10)}).
     *
     * <p>This Admin-only Notabschluss operation is used when the tournament organizer needs to
     * advance past a phase that has unresolved matches. All unfinished matches in the TOURNAMENT
     * (not just this phase) are cancelled via the {@link MatchLockdownService} (E48S04 reuse,
     * AC-IMPL-FORCE-COMPLETE-REUSES-LOCKDOWN). This ensures no orphaned match state remains.
     *
     * <p>Both the phase status update and the match cancellation execute within the SAME
     * {@code @Transactional} boundary under the SAME per-tournament DB row-lock (DEC-37 Clause B).
     * Publishes {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} on success.
     *
     * @param phaseId the phase UUID
     * @return the updated phase with {@code status = "COMPLETED"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code ACTIVE}
     * @throws IllegalArgumentException if no phase with the given id exists
     */
    Phase forceComplete(UUID phaseId);
}
