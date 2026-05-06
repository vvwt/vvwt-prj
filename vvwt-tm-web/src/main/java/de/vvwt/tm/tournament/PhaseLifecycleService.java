package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public port for phase lifecycle status transitions (DEC-35, E48S06).
 *
 * <p>Drives each phase through its status lifecycle:
 *
 * <ul>
 *   <li>PENDING → ACTIVE via {@link #start(UUID)}
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
     * Transitions the phase from {@code PENDING} to {@code ACTIVE}.
     *
     * <p>Acquires a per-tournament DB row-lock (DEC-37 Clause B) as the first read. Publishes
     * {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} on success.
     *
     * @param phaseId the phase UUID
     * @return the updated phase with {@code status = "ACTIVE"}
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the phase's current status is
     *     not {@code PENDING}
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
