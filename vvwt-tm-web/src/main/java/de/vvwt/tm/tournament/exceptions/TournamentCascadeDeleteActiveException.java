package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;

/**
 * Thrown when cascade-delete is attempted on an ACTIVE tournament (E48S13,
 * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
 *
 * <p>ACTIVE tournaments must be cancelled first via {@code TournamentLifecycleService.cancel()},
 * then deleted. HTTP 409 Conflict via {@link de.vvwt.tm.web.GlobalExceptionHandler}.
 *
 * @see <a href="DEC-35">DEC-35 — exceptions in {@code tournament.exceptions} public package</a>
 * @see <a href="E48S13">E48S13 — Tournament Admin Escape Hatch</a>
 */
public class TournamentCascadeDeleteActiveException extends ConflictException {

    /**
     * Constructs the exception for the given tournament ID.
     *
     * @param id the tournament UUID
     */
    public TournamentCascadeDeleteActiveException(UUID id) {
        super(
                "Tournament '"
                        + id
                        + "' is ACTIVE and cannot be deleted. "
                        + "Please cancel it first (Cancel → Delete).");
    }
}
