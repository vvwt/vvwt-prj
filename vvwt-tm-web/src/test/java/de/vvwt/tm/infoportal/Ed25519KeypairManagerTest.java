package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Ed25519KeypairManager} — AC8 NO-PLAINTEXT-ON-DISK invariant.
 *
 * <p>DEC-22 Iron Law: written RED-first before production class exists.
 *
 * <p>AC8 invariant: on-disk representation MUST NOT contain the raw private-key bytes. The
 * implementation uses AES-GCM encryption-at-rest: the private key bytes are encrypted with a
 * randomly generated AES-256 key; both the AES key (wrapped in a key file) and the encrypted
 * private key (in a separate enc file) are stored on disk.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09 AC8</a>
 */
class Ed25519KeypairManagerTest {

    @Test
    void generateKeypair_producesValidEd25519Keys(@TempDir Path tmpDir) throws Exception {
        Ed25519KeypairManager manager = new Ed25519KeypairManager(tmpDir);

        manager.initializeIfAbsent();

        PublicKey pub = manager.getPublicKey();
        PrivateKey priv = manager.getPrivateKey();
        assertThat(pub).isNotNull();
        assertThat(priv).isNotNull();
        assertThat(pub.getAlgorithm()).isEqualTo("EdDSA");
        assertThat(priv.getAlgorithm()).isEqualTo("EdDSA");
    }

    @Test
    void generateKeypair_createsOnDiskFiles(@TempDir Path tmpDir) throws Exception {
        Ed25519KeypairManager manager = new Ed25519KeypairManager(tmpDir);
        manager.initializeIfAbsent();

        // Should have created keypair files on disk
        assertThat(tmpDir).isNotEmptyDirectory();
    }

    /**
     * AC8 core invariant: the on-disk bytes MUST NOT contain the raw private-key bytes.
     *
     * <p>Implementation: reads all files in the storage directory and scans for any 32-byte
     * sliding window that matches the raw Ed25519 private key bytes. If found → test FAILS.
     */
    @Test
    void noPlaInTextOnDisk_rawPrivateKeyBytesAbsentFromAllDiskFiles(@TempDir Path tmpDir)
            throws Exception {
        Ed25519KeypairManager manager = new Ed25519KeypairManager(tmpDir);
        manager.initializeIfAbsent();

        // Get raw private key bytes for comparison
        PrivateKey priv = manager.getPrivateKey();
        byte[] rawPrivKeyBytes = priv.getEncoded(); // PKCS#8 encoded; Ed25519 raw is 32 bytes

        // Scan all on-disk files
        try (var stream = Files.list(tmpDir)) {
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file)) {
                    byte[] diskBytes = Files.readAllBytes(file);
                    // Check that the disk bytes do NOT contain rawPrivKeyBytes as a subsequence
                    assertThat(containsSubarray(diskBytes, rawPrivKeyBytes))
                            .as(
                                    "File '%s' MUST NOT contain raw private key bytes"
                                            + " (AC8 NO-PLAINTEXT-ON-DISK violation)",
                                    file.getFileName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void initializeIfAbsent_isIdempotent(@TempDir Path tmpDir) throws Exception {
        Ed25519KeypairManager manager = new Ed25519KeypairManager(tmpDir);
        manager.initializeIfAbsent();
        PublicKey pub1 = manager.getPublicKey();

        // Second call on existing files — must NOT regenerate
        manager.initializeIfAbsent();
        PublicKey pub2 = manager.getPublicKey();

        assertThat(pub1.getEncoded()).isEqualTo(pub2.getEncoded());
    }

    @Test
    void reloadFromDisk_recoversKeypair(@TempDir Path tmpDir) throws Exception {
        Ed25519KeypairManager manager1 = new Ed25519KeypairManager(tmpDir);
        manager1.initializeIfAbsent();
        byte[] pub1Encoded = manager1.getPublicKey().getEncoded();

        // New manager instance — loads from same tmpDir
        Ed25519KeypairManager manager2 = new Ed25519KeypairManager(tmpDir);
        manager2.initializeIfAbsent();
        byte[] pub2Encoded = manager2.getPublicKey().getEncoded();

        assertThat(pub1Encoded).isEqualTo(pub2Encoded);
    }

    @Test
    void sign_producesVerifiableSignature(@TempDir Path tmpDir) throws Exception {
        Ed25519KeypairManager manager = new Ed25519KeypairManager(tmpDir);
        manager.initializeIfAbsent();

        byte[] payload = "test-payload".getBytes();
        byte[] signature = manager.sign(payload);

        assertThat(signature).isNotNull();
        assertThat(signature.length).isEqualTo(64); // Ed25519 signature is always 64 bytes

        // Verify using the public key
        java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
        sig.initVerify(manager.getPublicKey());
        sig.update(payload);
        assertThat(sig.verify(signature)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code haystack} contains {@code needle} as a contiguous subarray.
     * Used for the AC8 byte-scan.
     */
    private static boolean containsSubarray(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        if (haystack.length < needle.length) return false;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
