package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a PIN is supplied during assignment of a DISPLAY device (E49S01 AC5).
 *
 * <p>DISPLAY devices have no PIN. A caller supplying one indicates a client-side bug. Results in
 * HTTP 422 Unprocessable Entity.
 *
 * @see <a href="E49S01">E49S01 — AC5: PIN forbidden for DISPLAY assignment</a>
 */
public class UnexpectedPinForDisplayException extends RuntimeException {

    /** Constructs an {@code UnexpectedPinForDisplayException} with a fixed message. */
    public UnexpectedPinForDisplayException() {
        super("PIN must not be supplied when assigning a DISPLAY device");
    }
}
