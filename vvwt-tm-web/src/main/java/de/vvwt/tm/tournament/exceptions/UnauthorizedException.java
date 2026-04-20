package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a request lacks valid credentials or the credentials have expired (E21S09,
 * AC-TDD-UnauthorizedException, AC-PKG-UnauthorizedException).
 *
 * <p>This is the new boundary-API exception at {@code de.vvwt.tm.tournament.exceptions.*} per
 * DEC-21 (D-8 package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.UnauthorizedException} at atomic cutover time. During the parallel-development
 * phase, both coexist.
 *
 * <p>Mapped to HTTP 401 by {@code GlobalExceptionHandler} (E21S10).
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause
 * (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see ForbiddenException for HTTP 403
 * @see de.vvwt.tm.domain.UnauthorizedException legacy counterpart (untouched until cutover)
 */
public class UnauthorizedException extends RuntimeException {

    /**
     * Constructs an {@code UnauthorizedException} with a human-readable message.
     *
     * @param message human-readable description of the authorization failure
     */
    public UnauthorizedException(String message) {
        super(message);
    }

    /**
     * Constructs an {@code UnauthorizedException} with a message and the underlying cause.
     *
     * @param message human-readable description of the authorization failure
     * @param cause the underlying cause (preserved by {@link Throwable#getCause()})
     */
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
