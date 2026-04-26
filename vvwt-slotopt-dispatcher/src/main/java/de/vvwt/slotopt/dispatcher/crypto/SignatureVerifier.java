package de.vvwt.slotopt.dispatcher.crypto;

/**
 * SPI for verifying digital signatures in the slot-optimization dispatcher.
 *
 * <p>This interface is the algorithm-agility extension point defined by DEC-43 / E37S04
 * AC-SIGNATURE-VERIFIER-INTERFACE. V1 ships a single implementation ({@link
 * de.vvwt.slotopt.dispatcher.crypto.internal.DefaultEd25519Verifier}) which handles Ed25519 (RFC
 * 8032). Future implementations (e.g., ML-DSA-65) register as additional Spring {@code @Component}
 * beans — no existing code changes required (open/closed principle per DEC-43 § Future PQC
 * migration is a code-only path).
 *
 * <h2>Package layout (DEC-35 / DEC-43)</h2>
 *
 * <p>This interface lives in the {@code crypto} package root (public surface). Implementations live
 * in {@code crypto.internal}. All consumers type their fields as {@code SignatureVerifier} (or
 * {@code SignatureVerifierRegistry}), never as {@code DefaultEd25519Verifier}.
 *
 * <h2>Error contract</h2>
 *
 * <p>A technically-valid signature that is simply incorrect (wrong bytes) is signalled by returning
 * {@code false}, NOT by throwing {@link InvalidSignatureException}. The exception is reserved for
 * structural failures: malformed key length, internal JCE errors, or unsupported key encoding.
 *
 * <p>Spec: E37S04 AC-SIGNATURE-VERIFIER-INTERFACE; DEC-43 § D1 (SPI shape).
 */
public interface SignatureVerifier {

    /**
     * Returns the server-canonical algorithm identifier for this verifier.
     *
     * <p>Identifier uses the JCE canonical name form (e.g., {@code "Ed25519"}, {@code
     * "ML-DSA-65"}). The identifier is stable across server restarts and is transmitted in the
     * {@code supportedAlgorithms[]} list in registration responses (DEC-43 § D1).
     *
     * @return the algorithm identifier; never {@code null}
     */
    String algorithmId();

    /**
     * Returns the minimum accepted public key length in bytes for this algorithm.
     *
     * <p>For Ed25519 this is {@code 32} (RFC 8032). A public key shorter than this value causes
     * {@link #verify} to throw {@link InvalidSignatureException}.
     *
     * @return the minimum public key length in bytes
     */
    int minPublicKeyBytes();

    /**
     * Returns the maximum accepted public key length in bytes for this algorithm.
     *
     * <p>For Ed25519 this is {@code 32} (RFC 8032). A public key longer than this value causes
     * {@link #verify} to throw {@link InvalidSignatureException}.
     *
     * @return the maximum public key length in bytes
     */
    int maxPublicKeyBytes();

    /**
     * Verifies the given signature over the given message using the given public key.
     *
     * <p>Ed25519 signs the message bytes DIRECTLY — NO pre-hashing by the caller (per E37S02 spec
     * §(a) shared concerns, RFC 8032).
     *
     * @param publicKey the raw public key bytes; must have length in [{@link #minPublicKeyBytes()},
     *     {@link #maxPublicKeyBytes()}]
     * @param message the message bytes that were signed; must not be {@code null}
     * @param signature the signature bytes; must not be {@code null}
     * @return {@code true} if the signature is valid for the given key and message; {@code false}
     *     if the signature is well-formed but incorrect
     * @throws InvalidSignatureException if the public key length is outside the valid range, or if
     *     an internal JCE error prevents verification
     */
    boolean verify(byte[] publicKey, byte[] message, byte[] signature)
            throws InvalidSignatureException;
}
