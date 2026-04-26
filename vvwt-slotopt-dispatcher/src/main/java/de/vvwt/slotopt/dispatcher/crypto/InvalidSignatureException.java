package de.vvwt.slotopt.dispatcher.crypto;

/**
 * Thrown when signature verification fails or a public key is malformed.
 *
 * <p>This exception is part of the {@code crypto} SPI (DEC-43, E37S04
 * AC-INVALID-SIGNATURE-EXCEPTION). It signals:
 *
 * <ul>
 *   <li>A public key with an unexpected length (e.g., not 32 bytes for Ed25519).
 *   <li>An internal JCE failure during key factory or signature initialisation.
 * </ul>
 *
 * <p>Note: a signature that is well-formed but simply incorrect is NOT an {@code
 * InvalidSignatureException} — {@link de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier#verify}
 * returns {@code false} in that case.
 *
 * <p>See AC-INVALID-SIGNATURE-EXCEPTION, AC-DEFAULT-ED25519-VERIFIER (E37S04).
 */
public class InvalidSignatureException extends RuntimeException {

    /**
     * Constructs an {@code InvalidSignatureException} with the given detail message.
     *
     * @param message the detail message (never {@code null} in production callers)
     */
    public InvalidSignatureException(String message) {
        super(message);
    }

    /**
     * Constructs an {@code InvalidSignatureException} with the given detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying JCE or IO exception that triggered this failure
     */
    public InvalidSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
