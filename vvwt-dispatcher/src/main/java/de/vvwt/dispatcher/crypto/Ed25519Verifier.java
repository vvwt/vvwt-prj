package de.vvwt.dispatcher.crypto;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Ed25519 signature verification using the JDK 21 native provider.
 *
 * <p>Ed25519 signs raw message bytes directly per RFC 8032 — NO pre-hashing.
 * The caller is responsible for providing the canonical message bytes (e.g.
 * JCS-canonicalized JSON UTF-8) before calling {@link #verify}.
 *
 * <p>This class is a pure stateless utility; all methods are static.
 * Thread-safe.
 *
 * <p>See Story E01S06 AC6 and DEC-6.
 */
public final class Ed25519Verifier {

    /** SubjectPublicKeyInfo DER prefix for Ed25519 (RFC 8410 OID 1.3.101.112). */
    private static final byte[] ED25519_SPKI_PREFIX = {
        0x30, 0x2a,  // SEQUENCE, 42 bytes total
        0x30, 0x05,  // SEQUENCE (AlgorithmIdentifier), 5 bytes
        0x06, 0x03,  // OID, 3 bytes
        0x2b, 0x65, 0x70,  // 1.3.101.112 (id-EdDSA / Ed25519)
        0x03, 0x21,  // BIT STRING, 33 bytes
        0x00         // no unused bits
        // followed by 32 raw key bytes
    };

    /** Raw Ed25519 public key length in bytes (RFC 8032). */
    private static final int RAW_KEY_LENGTH = 32;

    private Ed25519Verifier() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Reconstructs an Ed25519 {@link PublicKey} from raw 32-byte key material.
     *
     * <p>The 32 raw bytes are wrapped in a SubjectPublicKeyInfo DER envelope
     * (RFC 8410) so the JDK {@code KeyFactory} can parse them.
     *
     * @param rawPublicKeyBytes exactly 32 bytes of raw Ed25519 public key material
     * @return a {@link PublicKey} usable with {@code Signature.getInstance("Ed25519")}
     * @throws IllegalArgumentException if {@code rawPublicKeyBytes} is null or not 32 bytes
     * @throws InvalidSignatureException if the bytes cannot be parsed as a valid Ed25519 key
     */
    public static PublicKey decodePublicKey(byte[] rawPublicKeyBytes) {
        if (rawPublicKeyBytes == null) {
            throw new IllegalArgumentException("rawPublicKeyBytes must not be null");
        }
        if (rawPublicKeyBytes.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Ed25519 public key must be exactly 32 bytes, got: " + rawPublicKeyBytes.length);
        }

        // Build SubjectPublicKeyInfo DER: prefix || raw key bytes
        byte[] spki = new byte[ED25519_SPKI_PREFIX.length + RAW_KEY_LENGTH];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(rawPublicKeyBytes, 0, spki, ED25519_SPKI_PREFIX.length, RAW_KEY_LENGTH);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            return keyFactory.generatePublic(new X509EncodedKeySpec(spki));
        } catch (NoSuchAlgorithmException noSuchAlgorithm) {
            // Ed25519 is available in all JDK 21 installations (required by DEC-10)
            throw new IllegalStateException(
                    "Ed25519 KeyFactory not available — JDK 21 required (DEC-10)", noSuchAlgorithm);
        } catch (InvalidKeySpecException invalidSpec) {
            throw new InvalidSignatureException(
                    "Cannot parse Ed25519 public key bytes: " + invalidSpec.getMessage(), invalidSpec);
        }
    }

    /**
     * Verifies an Ed25519 signature.
     *
     * <p>The signature is verified against {@code messageBytes} directly — Ed25519
     * does NOT pre-hash (RFC 8032 §5.1). Callers MUST provide the canonical message
     * bytes (AC6: JCS-canonicalized UTF-8 bytes of the {@code phaseDef} field).
     *
     * @param rawPublicKeyBytes 32-byte raw Ed25519 public key
     * @param messageBytes      the canonical message that was signed
     * @param signatureBytes    the Ed25519 signature to verify (expected: 64 bytes)
     * @throws InvalidSignatureException if the signature is invalid or the key cannot be parsed
     * @throws IllegalArgumentException  if any argument is null
     * @throws IllegalStateException     if the JVM does not support Ed25519 (should not happen on JDK 21)
     */
    public static void verify(byte[] rawPublicKeyBytes, byte[] messageBytes, byte[] signatureBytes) {
        if (messageBytes == null) {
            throw new IllegalArgumentException("messageBytes must not be null");
        }
        if (signatureBytes == null) {
            throw new IllegalArgumentException("signatureBytes must not be null");
        }

        PublicKey publicKey = decodePublicKey(rawPublicKeyBytes);

        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(messageBytes);
            boolean valid = sig.verify(signatureBytes);
            if (!valid) {
                throw new InvalidSignatureException("Ed25519 signature verification failed");
            }
        } catch (NoSuchAlgorithmException noSuchAlgorithm) {
            throw new IllegalStateException(
                    "Ed25519 Signature not available — JDK 21 required (DEC-10)", noSuchAlgorithm);
        } catch (InvalidKeyException invalidKey) {
            throw new InvalidSignatureException(
                    "Ed25519 public key rejected by JCA: " + invalidKey.getMessage(), invalidKey);
        } catch (SignatureException signatureException) {
            throw new InvalidSignatureException(
                    "Ed25519 signature verification error: " + signatureException.getMessage(),
                    signatureException);
        }
    }
}
