package de.vvwt.slotopt.dispatcher.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefaultEd25519Verifier}.
 *
 * <p>RED-first per DEC-22 / AC-DEFAULT-ED25519-VERIFIER. Written before production classes exist.
 *
 * <p>RFC 8032 official test vector (§A.3 — Test Vector for Ed25519) used as spec-anchored test per
 * DEC-41 §1(c). Vector sourced from RFC 8032 §A.3. @SpecSource RFC 8032 §A.3 — Test Vector for
 * Ed25519 https://www.rfc-editor.org/rfc/rfc8032#appendix-A.3
 */
class DefaultEd25519VerifierTest {

    /**
     * RFC 8032 §A.3 — Test Vector 1 (the first test vector, smallest input).
     *
     * <pre>
     * PRIVATE KEY:  9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae3d55
     * PUBLIC KEY:   d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
     * MESSAGE:      (empty)
     * SIGNATURE:    e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155
     *               5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b
     * </pre>
     *
     * @SpecSource RFC 8032 §A.3, Test 1
     */
    private static final byte[] RFC8032_PUBLIC_KEY =
            HexFormat.of()
                    .parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");

    private static final byte[] RFC8032_MESSAGE = new byte[0]; // empty message

    private static final byte[] RFC8032_SIGNATURE =
            HexFormat.of()
                    .parseHex(
                            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    private DefaultEd25519Verifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new DefaultEd25519Verifier();
    }

    // ----------------------------------------------------------------
    // AC-DEFAULT-ED25519-VERIFIER: SPI metadata
    // ----------------------------------------------------------------

    @Test
    void algorithmId_returnsEd25519() {
        assertThat(verifier.algorithmId()).isEqualTo("Ed25519");
    }

    @Test
    void minPublicKeyBytes_returns32() {
        assertThat(verifier.minPublicKeyBytes()).isEqualTo(32);
    }

    @Test
    void maxPublicKeyBytes_returns32() {
        assertThat(verifier.maxPublicKeyBytes()).isEqualTo(32);
    }

    @Test
    void implementsSignatureVerifier() {
        assertThat(verifier).isInstanceOf(SignatureVerifier.class);
    }

    // ----------------------------------------------------------------
    // AC-DEFAULT-ED25519-VERIFIER: RFC 8032 spec-anchored test vector
    // ----------------------------------------------------------------

    /**
     * RFC 8032 §A.3 Test 1 — empty message, known public key and signature → verify returns
     * true. @SpecSource RFC 8032 §A.3
     */
    @Test
    void verify_rfc8032TestVector1_returnsTrue() throws InvalidSignatureException {
        boolean result = verifier.verify(RFC8032_PUBLIC_KEY, RFC8032_MESSAGE, RFC8032_SIGNATURE);
        assertThat(result).isTrue();
    }

    // ----------------------------------------------------------------
    // AC-DEFAULT-ED25519-VERIFIER: happy path
    // ----------------------------------------------------------------

    @Test
    void verify_validSignature_returnsTrue() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        byte[] publicKeyBytes = kp.getPublic().getEncoded();
        // SubjectPublicKeyInfo encoding — extract raw 32 bytes (last 32 bytes)
        byte[] rawPub =
                java.util.Arrays.copyOfRange(
                        publicKeyBytes, publicKeyBytes.length - 32, publicKeyBytes.length);

        byte[] message = "hello dispatcher".getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(message);
        byte[] signature = sig.sign();

        boolean result = verifier.verify(rawPub, message, signature);
        assertThat(result).isTrue();
    }

    @Test
    void verify_invalidSignature_returnsFalse() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        byte[] publicKeyBytes = kp.getPublic().getEncoded();
        byte[] rawPub =
                java.util.Arrays.copyOfRange(
                        publicKeyBytes, publicKeyBytes.length - 32, publicKeyBytes.length);

        byte[] message = "hello dispatcher".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedSignature = new byte[64]; // all zeros — invalid

        boolean result = verifier.verify(rawPub, message, tamperedSignature);
        assertThat(result).isFalse();
    }

    // ----------------------------------------------------------------
    // AC-DEFAULT-ED25519-VERIFIER: error paths
    // ----------------------------------------------------------------

    @Test
    void verify_wrongLengthPublicKey_throwsInvalidSignatureException() {
        byte[] wrongLengthKey = new byte[31]; // should be 32
        byte[] message = "msg".getBytes(StandardCharsets.UTF_8);
        byte[] signature = new byte[64];

        assertThatThrownBy(() -> verifier.verify(wrongLengthKey, message, signature))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("32");
    }

    @Test
    void verify_oversizedPublicKey_throwsInvalidSignatureException() {
        byte[] oversizedKey = new byte[64]; // should be 32
        byte[] message = "msg".getBytes(StandardCharsets.UTF_8);
        byte[] signature = new byte[64];

        assertThatThrownBy(() -> verifier.verify(oversizedKey, message, signature))
                .isInstanceOf(InvalidSignatureException.class);
    }

    // ----------------------------------------------------------------
    // E40S01 AC-DEFAULTED25519VERIFIER-METADATA: new DEC-43 D1 metadata methods
    // RED-first per DEC-22 Iron Law; these tests FAIL until the methods are added
    // to SignatureVerifier interface and implemented in DefaultEd25519Verifier.
    // ----------------------------------------------------------------

    /**
     * AC-DEFAULTED25519VERIFIER-METADATA: displayName() returns "Ed25519" (PascalCase per Brief
     * D-4/T-3 backward-compat with E37-persisted algorithm column). AC-INTERFACE-METHODS-NO-THROWS-
     * AND-NULL-CONTRACT: return value is non-null (DEC-43 D1 required=YES for display_name).
     */
    @Test
    void displayName_returnsEd25519() {
        String name = verifier.displayName();
        assertThat(name).isEqualTo("Ed25519");
        assertThat(name).isNotNull();
    }

    /**
     * AC-DEFAULTED25519VERIFIER-METADATA: deprecationDate() returns null (V1 universal per Brief
     * D-4/C-17 — Ed25519 is not deprecated in V1).
     * AC-INTERFACE-METHODS-NO-THROWS-AND-NULL-CONTRACT: null IFF algorithm is not deprecated
     * (DEC-43 D1 required=NO for deprecation_date).
     */
    @Test
    void deprecationDate_returnsNull() {
        assertThat(verifier.deprecationDate()).isNull();
    }

    /**
     * AC-DEFAULTED25519VERIFIER-METADATA: parameters() returns null (V1 Ed25519 has no parameter
     * variants per Brief D-4). AC-INTERFACE-METHODS-NO-THROWS-AND-NULL-CONTRACT: null IFF algorithm
     * is parameterless (DEC-43 D1 required=NO for parameters).
     */
    @Test
    void parameters_returnsNull() {
        assertThat(verifier.parameters()).isNull();
    }
}
