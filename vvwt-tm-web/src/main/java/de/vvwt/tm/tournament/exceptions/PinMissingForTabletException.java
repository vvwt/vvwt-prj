package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a SCORING_TABLET assignment is attempted without supplying a PIN (E49S01 AC5).
 *
 * <p>Results in HTTP 422 Unprocessable Entity: the request is syntactically valid but missing a
 * semantically required field.
 *
 * @see <a href="E49S01">E49S01 — AC5: PIN mandatory for SCORING_TABLET assignment</a>
 */
public class PinMissingForTabletException extends RuntimeException {

    /** Constructs a {@code PinMissingForTabletException} with a fixed message. */
    public PinMissingForTabletException() {
        super("PIN is required when assigning a SCORING_TABLET device");
    }
}
