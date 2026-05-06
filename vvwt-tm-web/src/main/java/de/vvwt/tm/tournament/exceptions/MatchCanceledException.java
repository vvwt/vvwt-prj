package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a score submission is attempted on a match that has been cancelled (E48S04,
 * AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE).
 *
 * <p>Mapped to HTTP 409 Conflict by {@link de.vvwt.tm.web.GlobalExceptionHandler}.
 *
 * <p>The message carries operator-actionable information per
 * AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE: "Match {matchId} is CANCELED — score submission
 * rejected. Tournament was cancelled."
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause.
 * The message must not contain internal system details (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.tournament.MatchState#CANCELED
 * @see <a href="DEC-35">DEC-35 — exception in {@code tournament.exceptions} public package</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown</a>
 */
public class MatchCanceledException extends RuntimeException {

    /**
     * Constructs a {@code MatchCanceledException} with an operator-actionable message.
     *
     * @param message human-readable description including matchId and cancellation context
     */
    public MatchCanceledException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code MatchCanceledException} with a message and the underlying cause.
     *
     * @param message human-readable description
     * @param cause the underlying cause
     */
    public MatchCanceledException(String message, Throwable cause) {
        super(message, cause);
    }
}
