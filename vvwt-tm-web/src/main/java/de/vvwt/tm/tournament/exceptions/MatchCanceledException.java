package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a score submission is attempted on a match that has been cancelled (E48S04,
 * AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE, AC-IMPL-SCORE-SERVICE-GUARD).
 *
 * <p>Resides in {@code de.vvwt.tm.tournament.exceptions} (public Spring Modulith named-interface
 * sub-package, declared in {@code tournament::exceptions} per {@code web.allowedDependencies}) so
 * that the {@code web} module's {@link de.vvwt.tm.web.GlobalExceptionHandler} can map it to HTTP
 * 409 Conflict.
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST be operator-actionable and MUST NOT contain credentials, session tokens, raw SQL,
 * or internal stack information. The format is:
 *
 * <pre>
 * Match {matchId} is CANCELED — score submission rejected. Tournament was cancelled at {timestamp}.
 * </pre>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — exception at the public package surface of the {@code tournament} context
 *   <li>DEC-21 — accessible to {@code scoring.internal} via public tournament boundary
 *   <li>DEC-22 — introduced RED-first as part of E48S04's 4-test suite (this class is a compilation
 *       dependency of the RED test; the test fails to compile without it)
 * </ul>
 *
 * <p>Mapped to HTTP 409 Conflict by {@link de.vvwt.tm.web.GlobalExceptionHandler} via the {@code
 * handleMatchCanceled} handler added in E48S04.
 *
 * @see de.vvwt.tm.scoring.internal.DefaultScoreEntryService
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown Backend</a>
 */
public class MatchCanceledException extends RuntimeException {

    /**
     * Constructs a {@code MatchCanceledException} with an operator-actionable message.
     *
     * @param message operator-actionable description (must include matchId and "CANCELED"; must not
     *     contain credentials, SQL, or stack traces)
     */
    public MatchCanceledException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code MatchCanceledException} with a message and the underlying cause.
     *
     * @param message operator-actionable description
     * @param cause the underlying cause
     */
    public MatchCanceledException(String message, Throwable cause) {
        super(message, cause);
    }
}
