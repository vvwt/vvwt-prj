package de.vvwt.slotopt.worker.runtime;

/**
 * Thrown when the result signing operation fails.
 *
 * <p>Wraps JCE or key-management errors that occur during {@link ResultSigner#signResult}.
 *
 * <p>Story: E41S03 AC-SIGNING-EXCEPTION (moved to E63S01 shared runtime library).
 */
public class SigningException extends RuntimeException {

    /**
     * Constructs a new {@code SigningException}.
     *
     * @param message human-readable error description
     * @param cause the underlying cause
     */
    public SigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
