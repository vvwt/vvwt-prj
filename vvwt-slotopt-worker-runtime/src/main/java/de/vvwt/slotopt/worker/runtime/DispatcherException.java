package de.vvwt.slotopt.worker.runtime;

/**
 * Thrown when communication with the dispatcher fails.
 *
 * <p>Carries the HTTP status code when available (e.g., {@code 410} for deprecated algorithm,
 * {@code 204} for no-content on pull-packet), or {@code 0} for I/O failures before any HTTP
 * response was received.
 *
 * <p>Story: E41S04 AC-DISPATCHER-EXCEPTION (moved to E63S01 shared runtime library).
 */
public class DispatcherException extends RuntimeException {

    private final int httpStatus;

    /**
     * Constructs a new {@code DispatcherException}.
     *
     * @param httpStatus HTTP status code, or {@code 0} for I/O failures without an HTTP response
     * @param message human-readable error description
     * @param cause the underlying cause, or {@code null}
     */
    public DispatcherException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the HTTP status code from the dispatcher, or {@code 0} if the failure occurred before
     * any HTTP response was received.
     *
     * @return HTTP status code (e.g., 410, 204) or 0 for I/O failure
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
