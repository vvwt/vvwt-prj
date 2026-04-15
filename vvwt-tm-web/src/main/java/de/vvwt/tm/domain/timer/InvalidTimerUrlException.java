package de.vvwt.tm.domain.timer;

import java.util.UUID;

/**
 * Thrown when a timer URL references a tournament that does not exist for the active tenant.
 *
 * <p>Story E11S02 AC7 — maps to HTTP 404 with error code {@code "INVALID_TIMER_URL"}.
 * This is distinct from {@link NoActiveTournamentException} which signals that a valid tournament
 * exists but is in a non-timer-accessible status (DRAFT or CANCELLED).
 *
 * @see NoActiveTournamentException
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story E11S02</a>
 */
public class InvalidTimerUrlException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates the exception for an unknown tournament.
     *
     * @param tournamentId the tournament UUID that was not found
     */
    public InvalidTimerUrlException(UUID tournamentId) {
        super("No tournament found for timer URL — tournament=" + tournamentId
              + ". This combination is invalid.");
        this.errorCode = "INVALID_TIMER_URL";
    }

    /**
     * Returns the machine-readable error code for the timer UI to display a specific message.
     *
     * @return always {@code "INVALID_TIMER_URL"}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
