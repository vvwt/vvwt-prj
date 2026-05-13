package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a match-correction would produce a {@code FINISHED_STANDOFF} outcome for a match
 * format that does not allow ties (E48S25, AC-GUARD-STANDOFF-FORMAT).
 *
 * <p>Only {@code MatchFormat.FIXED_2_SETS} has {@code allowsTies = true}. Any correction that would
 * derive {@code FINISHED_STANDOFF} on a {@code BEST_OF_N} format is rejected with this exception →
 * HTTP 422 Unprocessable Entity via {@code GlobalExceptionHandler}.
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST NOT contain credentials, session tokens, or raw SQL (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see de.vvwt.tm.tournament.MatchFormat
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public class StandoffFormatMismatchException extends RuntimeException {

    /**
     * Constructs a {@code StandoffFormatMismatchException} with a human-readable message.
     *
     * @param message human-readable description of the format mismatch (must not contain
     *     credentials, SQL, or stack traces)
     */
    public StandoffFormatMismatchException(String message) {
        super(message);
    }
}
