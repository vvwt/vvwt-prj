package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;

/**
 * Thrown when reset-plan is attempted on a COMPLETED tournament (E48S13,
 * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
 *
 * <p>COMPLETED tournaments cannot have their Phasenplan reset. HTTP 409 Conflict via {@link
 * de.vvwt.tm.web.GlobalExceptionHandler}.
 *
 * @see <a href="DEC-35">DEC-35 — exceptions in {@code tournament.exceptions} public package</a>
 * @see <a href="E48S13">E48S13 — Tournament Admin Escape Hatch</a>
 */
public class TournamentResetPlanCompletedException extends ConflictException {

    /**
     * Constructs the exception for the given tournament ID.
     *
     * @param id the tournament UUID
     */
    public TournamentResetPlanCompletedException(UUID id) {
        super("Tournament '" + id + "' is COMPLETED — Phasenplan cannot be reset.");
    }
}
