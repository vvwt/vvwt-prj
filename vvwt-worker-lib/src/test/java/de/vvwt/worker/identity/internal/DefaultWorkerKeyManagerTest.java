package de.vvwt.worker.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.vvwt.worker.identity.KeyRotationResult;
import de.vvwt.worker.identity.WorkerKeyCorruptException;
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
 * TDD-first unit tests for {@link DefaultWorkerKeyManager}.
 *
 * <p>Written RED-first per DEC-22 Iron Law before DefaultWorkerKeyManager implementation existed.
 * All test methods target the concrete implementation class directly (same-package white-box access
 * per DEC-36 — this test class is in {@code de.vvwt.worker.identity.internal}).
 *
 * <p>Replaces the Snapshot-Driven {@code WorkerKeyManagerTest} per DEC-41 hierarchy clause 3
 * (E35S01 audit classified all 19 methods as Snapshot-Driven).
 *
 * <p>See E35S02, DEC-22, DEC-36, DEC-41.
 */
class DefaultWorkerKeyManagerTest {

    // -------------------------------------------------------------------------
    // AC1 (DEC-22 RED-first) — generate on first invocation, load on subsequent
    // -------------------------------------------------------------------------

    @Test
    void firstInvocationGeneratesKeypairFiles(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, logger);

        assertThat(tempDir.resolve("optimizer-worker.key")).exists();
        assertThat(tempDir.resolve("optimizer-worker.pub")).exists();
    }

    @Test
    void firstInvocationReturns32BytePublicKey(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    @Test
    void subsequentInvocationLoadsExistingKeypair(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager first = new DefaultWorkerKeyManager(tempDir, logger);
        byte[] publicKeyFirst = first.getPublicKeyBytes();

        DefaultWorkerKeyManager second = new DefaultWorkerKeyManager(tempDir, logger);
        byte[] publicKeySecond = second.getPublicKeyBytes();

        assertThat(publicKeySecond)
                .as("Same public key must be returned on subsequent load")
                .isEqualTo(publicKeyFirst);
    }

    // -------------------------------------------------------------------------
    // 0600 POSIX permissions on private key file
    // -------------------------------------------------------------------------

    @Test
    void privateKeyFileHas0600Permissions(@TempDir Path tempDir) throws Exception {
        boolean isPosix = Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
        assumeThat(isPosix).as("POSIX file attributes not supported on this platform").isTrue();

        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, logger);

        Path privateKeyFile = tempDir.resolve("optimizer-worker.key");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(privateKeyFile);

        assertThat(permissions)
                .as("Private key must have owner read+write only (0600)")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    // -------------------------------------------------------------------------
    // Corrupt private key → WorkerKeyCorruptException, file NOT overwritten
    // -------------------------------------------------------------------------

    @Test
    void corruptPrivateKeyThrowsExceptionAndDoesNotOverwrite(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, logger);

        Path privateKeyFile = tempDir.resolve("optimizer-worker.key");
        byte[] garbageBytes = new byte[] {0x00, 0x01, 0x02, 0x03};
        Files.write(privateKeyFile, garbageBytes);
        long corruptFileTimestamp = Files.getLastModifiedTime(privateKeyFile).toMillis();

        assertThatThrownBy(() -> new DefaultWorkerKeyManager(tempDir, logger))
                .isInstanceOf(WorkerKeyCorruptException.class)
                .satisfies(
                        ex -> {
                            WorkerKeyCorruptException wkce = (WorkerKeyCorruptException) ex;
                            assertThat(wkce.getKeyFilePath().toAbsolutePath().toString())
                                    .contains("optimizer-worker.key");
                        });

        assertThat(Files.getLastModifiedTime(privateKeyFile).toMillis())
                .as("Corrupt private key file must NOT be overwritten")
                .isEqualTo(corruptFileTimestamp);
        assertThat(Files.readAllBytes(privateKeyFile))
                .as("Corrupt private key file contents must be unchanged")
                .isEqualTo(garbageBytes);
    }

    // -------------------------------------------------------------------------
    // signResult — produces a 64-byte detached signature
    // -------------------------------------------------------------------------

    @Test
    void signResultProduces64Bytes(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        byte[] payload = "test-result-payload".getBytes();
        byte[] signature = manager.signResult(payload);

        assertThat(signature).hasSize(64);
    }

    @Test
    void signResultRejectsNullInput(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        assertThatThrownBy(() -> manager.signResult(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Ed25519 determinism
    // -------------------------------------------------------------------------

    @Test
    void signaturesAreDeterministicForSameInput(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

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
    // getPublicKeyBytes
    // -------------------------------------------------------------------------

    @Test
    void getPublicKeyBytesReturns32Bytes(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    @Test
    void getPublicKeyBytesReturnsCopy(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        byte[] first = manager.getPublicKeyBytes();
        first[0] = (byte) ~first[0];
        byte[] second = manager.getPublicKeyBytes();

        assertThat(second[0])
                .as("Mutation of returned array must not affect subsequent calls")
                .isNotEqualTo(first[0]);
    }

    // -------------------------------------------------------------------------
    // dataDir creation
    // -------------------------------------------------------------------------

    @Test
    void missingDataDirIsCreatedOnFirstInvocation(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        Path nonExistentSubDir = tempDir.resolve("subdir/nested");
        assertThat(nonExistentSubDir).doesNotExist();

        new DefaultWorkerKeyManager(nonExistentSubDir, logger);

        assertThat(nonExistentSubDir).isDirectory();
    }

    @Test
    void dataDirCreationFailureThrowsIOException(@TempDir Path tempDir) throws Exception {
        boolean isPosix = Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
        assumeThat(isPosix).as("This test requires POSIX file permission support").isTrue();

        Files.setPosixFilePermissions(
                tempDir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

        try {
            Logger logger = mock(Logger.class);
            Path childDir = tempDir.resolve("locked-child");

            assertThatThrownBy(() -> new DefaultWorkerKeyManager(childDir, logger))
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(
                    tempDir,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    // -------------------------------------------------------------------------
    // INFO log messages at generation and load time
    // -------------------------------------------------------------------------

    @Test
    void generationLogsInfoWithFingerprintOnFirstRun(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, logger);

        verify(logger).info(anyString(), any(), anyString());
    }

    @Test
    void loadLogsInfoWithFingerprintOnSubsequentRun(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, logger);
        new DefaultWorkerKeyManager(tempDir, logger);

        verify(logger, times(2)).info(anyString(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // rotateKeypair
    // -------------------------------------------------------------------------

    @Test
    void rotateKeypairReturnsNewFingerprintDifferentFromOld(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        byte[] originalPublicKey = manager.getPublicKeyBytes();
        KeyRotationResult result = manager.rotateKeypair();

        assertThat(result).isNotNull();
        assertThat(result.oldFingerprint()).isNotNull().hasSize(16);
        assertThat(result.newFingerprint()).isNotNull().hasSize(16);
        assertThat(result.oldFingerprint())
                .as("Old and new fingerprints must differ after rotation")
                .isNotEqualTo(result.newFingerprint());

        byte[] newPublicKey = manager.getPublicKeyBytes();
        assertThat(newPublicKey)
                .as("Public key bytes must change after rotation")
                .isNotEqualTo(originalPublicKey);
    }

    @Test
    void rotateKeypairUpdatesKeyFiles(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);

        byte[] originalPubKeyBytes = Files.readAllBytes(tempDir.resolve("optimizer-worker.pub"));
        manager.rotateKeypair();
        byte[] newPubKeyBytes = Files.readAllBytes(tempDir.resolve("optimizer-worker.pub"));

        assertThat(newPubKeyBytes)
                .as("Public key file must be updated after rotation")
                .isNotEqualTo(originalPubKeyBytes);

        assertThat(tempDir.resolve("optimizer-worker.key.new"))
                .as("Temporary .new key file must not exist after successful rotation")
                .doesNotExist();
    }

    @Test
    void rotatedKeypairCanSignAndBeLoaded(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, logger);
        manager.rotateKeypair();

        byte[] signature = manager.signResult("payload".getBytes());
        assertThat(signature).hasSize(64);

        DefaultWorkerKeyManager reloaded = new DefaultWorkerKeyManager(tempDir, logger);
        assertThat(reloaded.getPublicKeyBytes()).isEqualTo(manager.getPublicKeyBytes());
    }

    // -------------------------------------------------------------------------
    // fingerprint helper — package-private, tested directly (same-package white-box)
    // -------------------------------------------------------------------------

    @Test
    void fingerprintReturns16HexChars() {
        byte[] fakePublicKey = new byte[32];
        Arrays.fill(fakePublicKey, (byte) 0xAB);
        String fp = DefaultWorkerKeyManager.fingerprint(fakePublicKey);
        assertThat(fp).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void fingerprintIsDeterministic() {
        byte[] fakePublicKey = new byte[32];
        Arrays.fill(fakePublicKey, (byte) 0x77);
        String fp1 = DefaultWorkerKeyManager.fingerprint(fakePublicKey);
        String fp2 = DefaultWorkerKeyManager.fingerprint(fakePublicKey);
        assertThat(fp1).isEqualTo(fp2);
    }
}
