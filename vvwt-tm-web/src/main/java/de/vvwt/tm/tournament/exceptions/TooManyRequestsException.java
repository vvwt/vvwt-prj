package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a resource limit is exceeded (E21S09, AC-TDD-TooManyRequestsException,
 * AC-PKG-TooManyRequestsException).
 *
 * <p>This is the new boundary-API exception at {@code de.vvwt.tm.tournament.exceptions.*} per
 * DEC-21 (D-8 package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.TooManyRequestsException} at atomic cutover time. During the
 * parallel-development phase, both coexist.
 *
 * <p>Mapped to HTTP 429 by {@code GlobalExceptionHandler} (E21S10).
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause
 * (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.domain.TooManyRequestsException legacy counterpart (untouched until cutover)
 */
public class TooManyRequestsException extends RuntimeException {

    /**
     * Constructs a {@code TooManyRequestsException} with a human-readable message.
     *
     * @param message human-readable description of the limit exceeded
     */
    public TooManyRequestsException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code TooManyRequestsException} with a message and the underlying cause.
     *
     * @param message human-readable description of the limit exceeded
     * @param cause the underlying cause (preserved by {@link Throwable#getCause()})
     */
    public TooManyRequestsException(String message, Throwable cause) {
        super(message, cause);
    }
}
