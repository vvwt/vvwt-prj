package de.vvwt.tm.timer;

import java.util.UUID;

/**
 * Thrown when a timer URL references a tournament that does not exist for the active tenant.
 *
 * <p>Maps to HTTP 404 with error code {@code "INVALID_TIMER_URL"}. This is distinct from {@link
 * NoActiveTournamentException} which signals that a valid tournament exists but is in a
 * non-timer-accessible status (DRAFT or CANCELLED).
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.InvalidTimerUrlException} per DEC-21 module layout.
 * Caught cross-module by {@code web.GlobalExceptionHandler.handleInvalidTimerUrl} (E26S03 updates
 * the import). UUID-argument constructor preserved verbatim per C-3 signature-preservation.
 *
 * @see NoActiveTournamentException
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class InvalidTimerUrlException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates the exception for an unknown tournament.
     *
     * @param tournamentId the tournament UUID that was not found
     */
    public InvalidTimerUrlException(UUID tournamentId) {
        super(
                "No tournament found for timer URL — tournament="
                        + tournamentId
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
