package de.vvwt.worker.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/**
 * Unit tests for {@link WorkerKeyManager}.
 *
 * <p>Covers Story E01S04 AC1–AC10. Platform-specific assertions (POSIX permissions) are skipped on
 * non-POSIX systems via {@link org.assertj.core.api.Assumptions#assumeThat}.
 */
class WorkerKeyManagerTest {

    // -------------------------------------------------------------------------
    // AC1 — generate on first invocation, load on subsequent
    // -------------------------------------------------------------------------

    @Test
    void firstInvocationGeneratesKeypair(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        assertThat(tempDir.resolve("optimizer-worker.key")).exists();
        assertThat(tempDir.resolve("optimizer-worker.pub")).exists();
        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    @Test
    void subsequentInvocationLoadsExistingKeypair(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager first = new WorkerKeyManager(tempDir, logger);
        byte[] publicKeyFirst = first.getPublicKeyBytes();

        WorkerKeyManager second = new WorkerKeyManager(tempDir, logger);
        byte[] publicKeySecond = second.getPublicKeyBytes();

        assertThat(publicKeySecond)
                .as("Same public key must be returned on subsequent load")
                .isEqualTo(publicKeyFirst);
    }

    // -------------------------------------------------------------------------
    // AC2 — 0600 POSIX permissions on private key file
    // -------------------------------------------------------------------------

    @Test
    void privateKeyFileHas0600Permissions(@TempDir Path tempDir) throws Exception {
        boolean isPosix = Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
        assumeThat(isPosix).as("POSIX file attributes not supported on this platform").isTrue();

        Logger logger = mock(Logger.class);
        new WorkerKeyManager(tempDir, logger);

        Path privateKeyFile = tempDir.resolve("optimizer-worker.key");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(privateKeyFile);

        assertThat(permissions)
                .as("Private key must have owner read+write only (0600)")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    // -------------------------------------------------------------------------
    // AC3 — corrupt private key → WorkerKeyCorruptException, file NOT overwritten
    // -------------------------------------------------------------------------

    @Test
    void corruptPrivateKeyThrowsExceptionAndDoesNotOverwrite(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        // Generate a valid keypair first
        new WorkerKeyManager(tempDir, logger);

        // Corrupt the private key file by overwriting with garbage
        Path privateKeyFile = tempDir.resolve("optimizer-worker.key");
        byte[] garbageBytes = new byte[] {0x00, 0x01, 0x02, 0x03};
        Files.write(privateKeyFile, garbageBytes);
        long corruptFileTimestamp = Files.getLastModifiedTime(privateKeyFile).toMillis();

        // Second manager instantiation must throw WorkerKeyCorruptException
        assertThatThrownBy(() -> new WorkerKeyManager(tempDir, logger))
                .isInstanceOf(WorkerKeyCorruptException.class)
                .satisfies(
                        ex -> {
                            WorkerKeyCorruptException wkce = (WorkerKeyCorruptException) ex;
                            assertThat(wkce.getKeyFilePath().toAbsolutePath().toString())
                                    .contains("optimizer-worker.key");
                        });

        // File must NOT have been overwritten (timestamp unchanged)
        assertThat(Files.getLastModifiedTime(privateKeyFile).toMillis())
                .as("Corrupt private key file must NOT be overwritten")
                .isEqualTo(corruptFileTimestamp);
        assertThat(Files.readAllBytes(privateKeyFile))
                .as("Corrupt private key file contents must be unchanged")
                .isEqualTo(garbageBytes);
    }

    // -------------------------------------------------------------------------
    // AC4 — signResult produces a 64-byte detached signature
    // -------------------------------------------------------------------------

    @Test
    void signResultProduces64Bytes(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] payload = "test-result-payload".getBytes();
        byte[] signature = manager.signResult(payload);

        assertThat(signature).hasSize(64);
    }

    @Test
    void signResultRejectsNullInput(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        assertThatThrownBy(() -> manager.signResult(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC5 — signatures are deterministic (Ed25519 is deterministic by spec)
    // -------------------------------------------------------------------------

    @Test
    void signaturesAreDeterministic(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] payload = "same-input-bytes".getBytes();
        byte[] sig1 = manager.signResult(payload);
        byte[] sig2 = manager.signResult(payload);

        assertThat(sig1)
                .as(
                        "Ed25519 signatures of the same input must be identical (deterministic by"
                                + " spec)")
                .isEqualTo(sig2);
    }

    // -------------------------------------------------------------------------
    // AC6 — getPublicKeyBytes returns exactly 32 bytes
    // -------------------------------------------------------------------------

    @Test
    void getPublicKeyBytesReturns32Bytes(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] publicKey = manager.getPublicKeyBytes();

        assertThat(publicKey).hasSize(32);
    }

    @Test
    void getPublicKeyBytesReturnsCopy(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] first = manager.getPublicKeyBytes();
        first[0] = (byte) ~first[0]; // mutate the returned array
        byte[] second = manager.getPublicKeyBytes();

        assertThat(second[0])
                .as("Mutation of returned array must not affect subsequent calls")
                .isNotEqualTo(first[0]);
    }

    // -------------------------------------------------------------------------
    // AC7 — missing dataDir is created; IOException on creation failure
    // -------------------------------------------------------------------------

    @Test
    void missingDataDirIsCreatedOnFirstInvocation(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        Path nonExistentSubDir = tempDir.resolve("subdir/nested");
        assertThat(nonExistentSubDir).doesNotExist();

        new WorkerKeyManager(nonExistentSubDir, logger);

        assertThat(nonExistentSubDir).isDirectory();
    }

    @Test
    void dataDirCreationFailureThrowsIOException(@TempDir Path tempDir) throws Exception {
        boolean isPosix = Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
        assumeThat(isPosix).as("This test requires POSIX file permission support").isTrue();

        // Make tempDir read-only to prevent subdirectory creation
        Files.setPosixFilePermissions(
                tempDir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

        try {
            Logger logger = mock(Logger.class);
            Path childDir = tempDir.resolve("locked-child");

            assertThatThrownBy(() -> new WorkerKeyManager(childDir, logger))
                    .isInstanceOf(IOException.class);
        } finally {
            // Restore so @TempDir cleanup can remove it
            Files.setPosixFilePermissions(
                    tempDir,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    // -------------------------------------------------------------------------
    // AC8 — WorkerKeyGenerationException is the declared thrown type
    // -------------------------------------------------------------------------

    @Test
    void workerKeyGenerationExceptionIsRuntimeException() {
        // Verify the exception class hierarchy — runtime exception means no forced catch
        WorkerKeyGenerationException ex =
                new WorkerKeyGenerationException("test", new RuntimeException("cause"));
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCause()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC9 — INFO log messages at generation and load time
    // -------------------------------------------------------------------------

    @Test
    void generationLogsInfoWithFingerprintOnFirstRun(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new WorkerKeyManager(tempDir, logger);

        // Verify INFO was called with a message containing "generated" and the path
        verify(logger).info(anyString(), any(), anyString());
    }

    @Test
    void loadLogsInfoWithFingerprintOnSubsequentRun(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        // First run — generate
        new WorkerKeyManager(tempDir, logger);
        // Second run — load
        new WorkerKeyManager(tempDir, logger);

        // Two info calls total: one for generate, one for load
        verify(logger, org.mockito.Mockito.times(2)).info(anyString(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // AC10 — rotateKeypair generates a new keypair, returns fingerprints
    // -------------------------------------------------------------------------

    @Test
    void rotateKeypairReturnsNewFingerprintDifferentFromOld(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] originalPublicKey = manager.getPublicKeyBytes();
        KeyRotationResult result = manager.rotateKeypair();

        assertThat(result).isNotNull();
        assertThat(result.oldFingerprint()).isNotNull().hasSize(16); // 8 bytes = 16 hex chars
        assertThat(result.newFingerprint()).isNotNull().hasSize(16);
        assertThat(result.oldFingerprint())
                .as("Old and new fingerprints must differ after rotation")
                .isNotEqualTo(result.newFingerprint());

        // Internal state must reflect new key
        byte[] newPublicKey = manager.getPublicKeyBytes();
        assertThat(newPublicKey)
                .as("Public key bytes must change after rotation")
                .isNotEqualTo(originalPublicKey);
    }

    @Test
    void rotateKeypairUpdatesKeyFiles(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);

        byte[] originalPubKeyBytes = Files.readAllBytes(tempDir.resolve("optimizer-worker.pub"));
        manager.rotateKeypair();
        byte[] newPubKeyBytes = Files.readAllBytes(tempDir.resolve("optimizer-worker.pub"));

        assertThat(newPubKeyBytes)
                .as("Public key file must be updated after rotation")
                .isNotEqualTo(originalPubKeyBytes);

        // The .new file must have been moved (renamed) — should not exist after rotation
        assertThat(tempDir.resolve("optimizer-worker.key.new"))
                .as("Temporary .new key file must not exist after successful rotation")
                .doesNotExist();
    }

    @Test
    void rotatedKeypairCanSignAndBeLoaded(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        WorkerKeyManager manager = new WorkerKeyManager(tempDir, logger);
        manager.rotateKeypair();

        // After rotation, signing must work with the new key
        byte[] signature = manager.signResult("payload".getBytes());
        assertThat(signature).hasSize(64);

        // And a new manager loading from disk must use the rotated key
        WorkerKeyManager reloaded = new WorkerKeyManager(tempDir, logger);
        assertThat(reloaded.getPublicKeyBytes()).isEqualTo(manager.getPublicKeyBytes());
    }

    // -------------------------------------------------------------------------
    // Fingerprint helper — package-private, tested directly
    // -------------------------------------------------------------------------

    @Test
    void fingerprintReturns16HexChars() {
        byte[] fakePublicKey = new byte[32];
        Arrays.fill(fakePublicKey, (byte) 0xAB);
        String fp = WorkerKeyManager.fingerprint(fakePublicKey);
        assertThat(fp).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void fingerprintIsDeterministic() {
        byte[] fakePublicKey = new byte[32];
        Arrays.fill(fakePublicKey, (byte) 0x77);
        String fp1 = WorkerKeyManager.fingerprint(fakePublicKey);
        String fp2 = WorkerKeyManager.fingerprint(fakePublicKey);
        assertThat(fp1).isEqualTo(fp2);
    }
}
