package de.vvwt.dispatcher.crypto;

/**
 * Thrown when an Ed25519 signature fails verification.
 *
 * <p>Unchecked — callers that need to distinguish invalid signatures from
 * internal errors may catch this type explicitly; all other exceptions from
 * {@link Ed25519Verifier} are {@link IllegalStateException}.
 */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }

    public InvalidSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
