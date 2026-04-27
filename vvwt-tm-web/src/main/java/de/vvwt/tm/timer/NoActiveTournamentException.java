package de.vvwt.tm.timer;

import java.util.UUID;

/**
 * Thrown when a timer URL references a valid tournament that is not in a timer-accessible state.
 *
 * <p>Maps to HTTP 404 with error code {@code "NO_ACTIVE_TOURNAMENT"}. Timer-accessible statuses are
 * {@code PLANNED}, {@code ACTIVE}, and {@code COMPLETED}. Tournaments in {@code DRAFT} or {@code
 * CANCELLED} status are not accessible via the timer.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.NoActiveTournamentException} per DEC-21 module layout.
 * Caught cross-module by {@code web.GlobalExceptionHandler.handleNoActiveTournament} (E26S03 updates
 * the import). (UUID, String) constructor preserved verbatim per C-3 signature-preservation.
 *
 * @see InvalidTimerUrlException
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class NoActiveTournamentException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates the exception for a tournament in a non-timer-accessible status.
     *
     * @param tournamentId the tournament UUID
     * @param status the current tournament status (e.g., {@code "DRAFT"})
     */
    public NoActiveTournamentException(UUID tournamentId, String status) {
        super(
                "Tournament "
                        + tournamentId
                        + " is in status '"
                        + status
                        + "' and has no active timer. Timer is available for PLANNED, ACTIVE, and"
                        + " COMPLETED tournaments.");
        this.errorCode = "NO_ACTIVE_TOURNAMENT";
    }

    /**
     * Returns the machine-readable error code for the timer UI to display a specific message.
     *
     * @return always {@code "NO_ACTIVE_TOURNAMENT"}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
