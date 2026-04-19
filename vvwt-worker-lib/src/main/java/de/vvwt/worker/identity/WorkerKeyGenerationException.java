package de.vvwt.worker.identity;

/**
 * Thrown when Ed25519 keypair generation fails — for example due to entropy starvation or a missing
 * JCE policy.
 *
 * <p>No retry is attempted and no fallback algorithm is used. The stronger algorithm is not
 * optional: falling back to a weaker algorithm would silently degrade the security contract
 * established in DEC-6 (asymmetric-key registration, Story E01S04 AC8).
 *
 * <p>The caller should treat this as a fatal startup failure and propagate it without catching.
 */
public class WorkerKeyGenerationException extends RuntimeException {

    /**
     * Constructs a new {@code WorkerKeyGenerationException}.
     *
     * @param message a human-readable description of the failure context
     * @param cause the underlying JCE or security exception
     */
    public WorkerKeyGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
