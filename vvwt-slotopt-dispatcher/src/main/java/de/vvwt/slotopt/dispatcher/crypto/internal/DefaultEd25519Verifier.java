package de.vvwt.slotopt.dispatcher.crypto.internal;

import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import org.springframework.stereotype.Component;

/**
 * V1 Ed25519 {@link SignatureVerifier} implementation using JDK 21 native cryptography.
 *
 * <p>Implements RFC 8032 (EdDSA — Ed25519). Uses JDK 21 native {@code
 * Signature.getInstance("Ed25519")} and {@code KeyFactory.getInstance("Ed25519")} — no Bouncy
 * Castle or other third-party crypto dependency (DEC-3 compliant).
 *
 * <h2>Key encoding</h2>
 *
 * <p>The JDK's Ed25519 {@link KeyFactory} requires a {@code SubjectPublicKeyInfo} (X.509 DER)
 * envelope. A raw 32-byte Ed25519 public key is wrapped into the standard ASN.1 header:
 *
 * <pre>
 * 30 2a                      -- SEQUENCE (42 bytes)
 *   30 05                    -- SEQUENCE (5 bytes)
 *     06 03 2b 65 70         -- OID 1.3.101.112 (id-EdDSA, Ed25519 per RFC 8410)
 *   03 21 00                 -- BIT STRING (33 bytes, 0 unused bits)
 *     {32 raw public key bytes}
 * </pre>
 *
 * <p>This header is prepended at verification time; callers supply raw 32-byte keys.
 *
 * <h2>Signing convention</h2>
 *
 * <p>Ed25519 signs message bytes DIRECTLY — NO external pre-hashing (RFC 8032 §5.1). Ed25519
 * internally uses SHA-512; external SHA-256 or SHA-512 pre-hashing is non-standard (per E37S02 spec
 * §(a)).
 *
 * <p>Spec: E37S04 AC-DEFAULT-ED25519-VERIFIER, AC-NO-NEW-NON-ED25519-VERIFIERS; DEC-3, DEC-43 §D4.
 */
@Component
public class DefaultEd25519Verifier implements SignatureVerifier {

    /**
     * ASN.1 / SubjectPublicKeyInfo header for Ed25519 raw public keys (RFC 8410).
     *
     * <p>Prepending these 12 bytes to the 32-byte raw key produces a valid DER-encoded X.509
     * SubjectPublicKeyInfo for Ed25519 that the JDK's {@code KeyFactory("Ed25519")} accepts.
     */
    private static final byte[] ED25519_SPKI_HEADER = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    /** Raw Ed25519 public key length in bytes (RFC 8032 §5.1.5). */
    private static final int ED25519_KEY_BYTES = 32;

    @Override
    public String algorithmId() {
        return "Ed25519";
    }

    @Override
    public int minPublicKeyBytes() {
        return ED25519_KEY_BYTES;
    }

    @Override
    public int maxPublicKeyBytes() {
        return ED25519_KEY_BYTES;
    }

    /**
     * {@inheritDoc}
     *
     * @throws InvalidSignatureException if {@code publicKey} is not exactly 32 bytes, or if a JCE
     *     error prevents key construction or signature initialisation
     */
    @Override
    public boolean verify(byte[] publicKey, byte[] message, byte[] signature)
            throws InvalidSignatureException {
        if (publicKey.length != ED25519_KEY_BYTES) {
            throw new InvalidSignatureException(
                    "Ed25519 public key must be exactly 32 bytes, got " + publicKey.length);
        }
        try {
            PublicKey pub = toJcaPublicKey(publicKey);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new InvalidSignatureException(
                    "Ed25519 verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a raw 32-byte Ed25519 public key to a JCA {@link PublicKey} by wrapping it in the
     * standard ASN.1 SubjectPublicKeyInfo envelope (RFC 8410).
     */
    private static PublicKey toJcaPublicKey(byte[] rawKey) throws GeneralSecurityException {
        byte[] der = new byte[ED25519_SPKI_HEADER.length + rawKey.length];
        System.arraycopy(ED25519_SPKI_HEADER, 0, der, 0, ED25519_SPKI_HEADER.length);
        System.arraycopy(rawKey, 0, der, ED25519_SPKI_HEADER.length, rawKey.length);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(der));
    }
}
