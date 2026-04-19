package de.vvwt.tm.domain.timer;

import java.util.UUID;

/**
 * Thrown when a timer URL references a valid tournament that is not in a timer-accessible state.
 *
 * <p>Story E11S02 AC7 — maps to HTTP 404 with error code {@code "NO_ACTIVE_TOURNAMENT"}.
 * Timer-accessible statuses are {@code PLANNED}, {@code ACTIVE}, and {@code COMPLETED}. Tournaments
 * in {@code DRAFT} or {@code CANCELLED} status are not accessible via the timer.
 *
 * <p>This is distinct from {@link InvalidTimerUrlException} which signals that the tournament
 * itself does not exist for the active tenant.
 *
 * @see InvalidTimerUrlException
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story
 *     E11S02</a>
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
