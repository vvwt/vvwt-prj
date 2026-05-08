package de.vvwt.tm.tournament;

import java.util.List;
import java.util.UUID;

/**
 * Public port for phase lifecycle status transitions (DEC-35, E48S06).
 *
 * <p>Drives each phase through its status lifecycle:
 *
 * <ul>
 *   <li>PENDING → PREPARED via {@link #prepare(UUID)} (E48S17)
 *   <li>PREPARED → ACTIVE via {@link #start(UUID)} (E48S17 refactor; predecessor must be COMPLETED)
 *   <li>ACTIVE → COMPLETED (only when all matches are FINISHED_*) via {@link #complete(UUID)}
 *   <li>ACTIVE → COMPLETED + void unfinished matches via {@link #forceComplete(UUID)}
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
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle Service</a>
 */
public interface PhaseLifecycleService {

    /**
     * Transitions the phase from {@code PENDING} to {@code PREPARED} (E48S17).
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
     * @deprecated Use {@link #prepare(UUID, List)} to pass slot payload for match generation. This
     *     zero-argument form does not generate matches.
     */
    @Deprecated
    Phase prepare(UUID phaseId);

    /**
     * Transitions the phase from {@code PENDING} to {@code PREPARED} (E48S21 fix).
     *
     * <p>Delegates avatar persistence + match generation to {@link
     * de.vvwt.tm.tournament.PhaseTransitionService#commitTransition(UUID, List)} before flipping
     * status to PREPARED. All three operations execute within the same {@code @Transactional}
     * boundary under the DEC-37 per-tournament row-lock.
     *
     * <p>Idempotent: if the phase is already {@code PREPARED}, returns the phase unchanged without
     * calling {@code commitTransition} (no duplicate avatars or matches). If the phase is in any
     * other state, throws {@link de.vvwt.tm.tournament.exceptions.ConflictException}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read.
     *
     * @param phaseId the phase UUID
     * @param slots the confirmed team-to-(group, position) assignments from the Vorbereiten UI
     * @return the updated phase with {@code status = "PREPARED"} (or unchanged if already PREPARED)
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code PENDING} and not {@code PREPARED}
     * @throws IllegalArgumentException if {@code slots} is null or empty, or if no phase exists
     */
    Phase prepare(UUID phaseId, List<TeamAvatarProposal> slots);

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
