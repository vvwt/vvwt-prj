package de.vvwt.info.crypto.internal;

import de.vvwt.info.crypto.SignatureVerifier;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Ed25519 implementation of {@link SignatureVerifier} using the JDK 21 native {@code EdDSA}
 * provider (RFC 8032 conformant, no third-party crypto dependency — DEC-3).
 *
 * <p>Lives at {@code de.vvwt.info.crypto.internal.Ed25519SignatureVerifier} — the {@code .internal}
 * subpackage per DEC-35 naming canon (interface in module root, default impl in {@code .internal}).
 *
 * <p>The {@code publicKey} parameter is expected to be X.509 SubjectPublicKeyInfo encoded bytes
 * (the output of {@code PublicKey.getEncoded()} for Ed25519 keys generated via JDK's {@code
 * KeyPairGenerator.getInstance("Ed25519")}). This is the format carried in {@link
 * de.vvwt.info.dto.registration.RegistrationRequest#public_key()} after Base64 decoding.
 *
 * <p>Phase-1 DEC-43 D4: Ed25519 is the only V1 algorithm. Future ML-DSA / SLH-DSA verifiers will
 * implement the same {@link SignatureVerifier} interface; the {@link
 * de.vvwt.info.crypto.SignatureVerifierRegistry} dispatches by {@code algorithm_id}.
 *
 * @see de.vvwt.info.crypto.SignatureVerifier
 * @see de.vvwt.info.crypto.SignatureVerifierRegistry
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC2,
 *     AC6</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
public class Ed25519SignatureVerifier implements SignatureVerifier {

    /**
     * Verifies an Ed25519 signature.
     *
     * @param payload the original bytes that were signed
     * @param signature the Ed25519 signature bytes (64 bytes for Ed25519)
     * @param publicKey X.509 SubjectPublicKeyInfo encoded Ed25519 public key bytes (44 bytes for
     *     Ed25519 — 12-byte X.509 header + 32-byte raw key)
     * @return {@code true} if the signature is valid; {@code false} if verification fails for any
     *     reason (invalid key format, invalid signature, wrong payload — all map to {@code false})
     */
    @Override
    public boolean verify(byte[] payload, byte[] signature, byte[] publicKey) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(publicKey));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(payload);
            return sig.verify(signature);
        } catch (Exception e) {
            // Any exception during key parsing or signature verification means invalid
            return false;
        }
    }
}
