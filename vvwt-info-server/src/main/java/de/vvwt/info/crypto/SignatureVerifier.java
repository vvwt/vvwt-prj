package de.vvwt.info.crypto;

/**
 * Algorithm-agnostic signature verifier interface (AC6 / DEC-43 D4 future-readiness).
 *
 * <p>Lives at {@code de.vvwt.info.crypto.SignatureVerifier} — a public surface package, NOT in
 * {@code .internal}, per DEC-35 naming canon (interface in module root, default impl in {@code
 * .internal}).
 *
 * <p>The interface signature is algorithm-agnostic: {@code algorithm_id} dispatch is handled by the
 * {@link SignatureVerifierRegistry}. Future ML-DSA / SLH-DSA implementations plug in without
 * changing the interface or consumers (E38S05 publisher, E38S09 TM publisher).
 *
 * <p>Phase-1 implementation: {@link de.vvwt.info.crypto.internal.Ed25519SignatureVerifier} — JDK 21
 * native {@code Ed25519} provider per DEC-43 D4 (RFC 8032).
 *
 * @see de.vvwt.info.crypto.internal.Ed25519SignatureVerifier
 * @see SignatureVerifierRegistry
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC6</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
@FunctionalInterface
public interface SignatureVerifier {

    /**
     * Verifies a signature over a payload against a public key.
     *
     * @param payload the original bytes that were signed
     * @param signature the signature bytes to verify
     * @param publicKey the public key bytes to verify against
     * @return {@code true} if the signature is cryptographically valid; {@code false} otherwise
     */
    boolean verify(byte[] payload, byte[] signature, byte[] publicKey);
}
