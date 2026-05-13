package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a match-correction request is rejected because the containing phase is not in the
 * {@code ACTIVE} lifecycle state (E48S25, AC-GUARD-PHASE-STATUS).
 *
 * <p>Eligible correction states require an ACTIVE phase. Non-ACTIVE phases (PENDING, PREPARED,
 * ASSIGNED, COMPLETED) reject correction with this exception → HTTP 409 Conflict via {@code
 * GlobalExceptionHandler}.
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST NOT contain credentials, session tokens, or raw SQL (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public class PhaseStateGuardException extends RuntimeException {

    /**
     * Constructs a {@code PhaseStateGuardException} with a human-readable message.
     *
     * @param message human-readable description of the guard violation (must not contain
     *     credentials, SQL, or stack traces)
     */
    public PhaseStateGuardException(String message) {
        super(message);
    }
}
