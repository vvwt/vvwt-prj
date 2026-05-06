package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public port for cross-story tournament lifecycle support helpers (DEC-35, E48S02).
 *
 * <p>Exposes read-only helper operations consumed by later E48 stories (E48S06, E48S07) that need
 * to determine phase position within a tournament without acquiring a write lock. All methods are
 * pure reads — no DEC-37 Clause B lock is required.
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultTournamentLifecycleSupport} per DEC-35.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentLifecycleSupport
 * @see <a href="DEC-35">DEC-35 — interface in public Modulith package; implementation in
 *     .internal</a>
 * @see <a href="DEC-37">DEC-37 — DEC-37 Clause B lock NOT applicable (pure reads)</a>
 * @see <a href="E48S02">E48S02 — AC-IMPL-IS-LAST-PHASE-HELPER-INTERFACE</a>
 */
public interface TournamentLifecycleSupport {

    /**
     * Returns whether the given phase is the last (highest sequenceNumber) phase of its tournament.
     *
     * <p>Pure read — no write lock acquired (DEC-37 Clause B not applicable to reads).
     *
     * @param phaseId the phase UUID to check; must not be {@code null}
     * @return {@code true} if the phase has the highest {@code sequenceNumber} among all phases of
     *     its tournament; {@code false} otherwise
     * @throws IllegalArgumentException if {@code phaseId} is {@code null} or no phase with the
     *     given id exists (message: {@code "Phase not found: <phaseId>"})
     */
    boolean isLastPhase(UUID phaseId);
}
