package de.vvwt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Ed25519Verifier}.
 *
 * <p>Uses JDK 21 native Ed25519 to generate test keypairs and produce valid/invalid signatures.
 */
class Ed25519VerifierTest {

    private byte[] rawPublicKeyBytes;
    private KeyPair keyPair;
    private static final byte[] TEST_MESSAGE = "hello dispatcher".getBytes();

    @BeforeEach
    void generateKeypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        keyPair = kpg.generateKeyPair();
        // Extract raw 32-byte public key (last 32 bytes of SubjectPublicKeyInfo DER)
        byte[] encoded = keyPair.getPublic().getEncoded(); // 44 bytes for Ed25519
        rawPublicKeyBytes = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, rawPublicKeyBytes, 0, 32);
    }

    private byte[] sign(byte[] message) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(message);
        return sig.sign();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void validSignatureVerifiesSuccessfully() throws Exception {
        byte[] signature = sign(TEST_MESSAGE);
        assertThatNoException()
                .isThrownBy(
                        () -> Ed25519Verifier.verify(rawPublicKeyBytes, TEST_MESSAGE, signature));
    }

    @Test
    void differentMessageSameKeyCausesVerificationFailure() throws Exception {
        byte[] signature = sign(TEST_MESSAGE);
        byte[] differentMessage = "different message".getBytes();
        assertThatThrownBy(
                        () ->
                                Ed25519Verifier.verify(
                                        rawPublicKeyBytes, differentMessage, signature))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void corruptedSignatureCausesVerificationFailure() throws Exception {
        byte[] signature = sign(TEST_MESSAGE);
        signature[0] =
                (byte) (signature[0] ^ 0xFF); // flip first byte (explicit cast, E18S01/DEC-29)
        assertThatThrownBy(() -> Ed25519Verifier.verify(rawPublicKeyBytes, TEST_MESSAGE, signature))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void wrongPublicKeyCausesVerificationFailure() throws Exception {
        byte[] signature = sign(TEST_MESSAGE);
        // Generate a different keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair otherKeyPair = kpg.generateKeyPair();
        byte[] otherEncoded = otherKeyPair.getPublic().getEncoded();
        byte[] otherRawKey = new byte[32];
        System.arraycopy(otherEncoded, otherEncoded.length - 32, otherRawKey, 0, 32);
        assertThatThrownBy(() -> Ed25519Verifier.verify(otherRawKey, TEST_MESSAGE, signature))
                .isInstanceOf(InvalidSignatureException.class);
    }

    // -------------------------------------------------------------------------
    // decodePublicKey
    // -------------------------------------------------------------------------

    @Test
    void decodePublicKeySucceedsForValidBytes() {
        assertThatNoException()
                .isThrownBy(() -> Ed25519Verifier.decodePublicKey(rawPublicKeyBytes));
    }

    @Test
    void decodePublicKeyRejectsNullInput() {
        assertThatThrownBy(() -> Ed25519Verifier.decodePublicKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodePublicKeyRejectsWrongLength() {
        assertThatThrownBy(() -> Ed25519Verifier.decodePublicKey(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void decodePublicKeyAcceptsValidKeyBytes() {
        // The JDK accepts the 32-byte key during decode; validity is checked at verify time.
        // This test confirms that a structurally valid key (from a real keypair) decodes without
        // error.
        assertThatNoException()
                .isThrownBy(() -> Ed25519Verifier.decodePublicKey(rawPublicKeyBytes));
    }

    // -------------------------------------------------------------------------
    // verify — null checks
    // -------------------------------------------------------------------------

    @Test
    void verifyRejectsNullMessage() throws Exception {
        byte[] signature = sign(TEST_MESSAGE);
        assertThatThrownBy(() -> Ed25519Verifier.verify(rawPublicKeyBytes, null, signature))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyRejectsNullSignature() {
        assertThatThrownBy(() -> Ed25519Verifier.verify(rawPublicKeyBytes, TEST_MESSAGE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
