package de.vvwt.slotopt.worker.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.vvwt.slotopt.worker.identity.KeyRotationResult;
import de.vvwt.slotopt.worker.identity.MixedAlgorithmKeysException;
import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
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
 * per DEC-36 — this test class is in {@code de.vvwt.slotopt.worker.identity.internal}).
 *
 * <p>Replaces the Snapshot-Driven {@code WorkerKeyManagerTest} per DEC-41 hierarchy clause 3
 * (E35S01 audit classified all 19 methods as Snapshot-Driven).
 *
 * <p>E37S03 additions: algorithmId() method, D-4 file-naming convention, D-4 startup mismatch
 * scenarios (clean-other-only, mixed-state, configured-only). File naming changed from
 * {@code optimizer-worker.key/pub} to {@code worker-{algorithmId}.key/pub} per AC-D4-FILE-NAMING-CONVENTION.
 *
 * <p>See E35S02, E37S03, DEC-22, DEC-36, DEC-41.
 */
class DefaultWorkerKeyManagerTest {

    private static final String ED25519 = "Ed25519";

    // -------------------------------------------------------------------------
    // AC1 (DEC-22 RED-first) — generate on first invocation, load on subsequent
    // -------------------------------------------------------------------------

    @Test
    void firstInvocationGeneratesKeypairFiles(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThat(tempDir.resolve("worker-Ed25519.key")).exists();
        assertThat(tempDir.resolve("worker-Ed25519.pub")).exists();
    }

    @Test
    void firstInvocationReturns32BytePublicKey(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    @Test
    void subsequentInvocationLoadsExistingKeypair(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager first = new DefaultWorkerKeyManager(tempDir, ED25519, logger);
        byte[] publicKeyFirst = first.getPublicKeyBytes();

        DefaultWorkerKeyManager second = new DefaultWorkerKeyManager(tempDir, ED25519, logger);
        byte[] publicKeySecond = second.getPublicKeyBytes();

        assertThat(publicKeySecond)
                .as("Same public key must be returned on subsequent load")
                .isEqualTo(publicKeyFirst);
    }

    // -------------------------------------------------------------------------
    // AC-WORKERKEYMANAGER-ALGORITHMID (E37S03) — algorithmId() returns configured algorithm
    // -------------------------------------------------------------------------

    @Test
    void algorithmIdReturnsConfiguredAlgorithm(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThat(manager.algorithmId())
                .as("algorithmId() must return the configured algorithm")
                .isEqualTo("Ed25519");
    }

    // -------------------------------------------------------------------------
    // AC-D4-FILE-NAMING-CONVENTION (E37S03) — file naming uses algorithm suffix
    // -------------------------------------------------------------------------

    @Test
    void fileNamingConventionUsesAlgorithmSuffix(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThat(tempDir.resolve("worker-Ed25519.key"))
                .as("Private key file must use algorithm-suffix naming")
                .exists();
        assertThat(tempDir.resolve("worker-Ed25519.pub"))
                .as("Public key file must use algorithm-suffix naming")
                .exists();
        assertThat(tempDir.resolve("optimizer-worker.key"))
                .as("Old file naming must not be used")
                .doesNotExist();
        assertThat(tempDir.resolve("optimizer-worker.pub"))
                .as("Old file naming must not be used")
                .doesNotExist();
    }

    // -------------------------------------------------------------------------
    // AC-D4-CLEAN-OTHER-ONLY-DETECTED (E37S03) — startup with other-algo files only
    // -------------------------------------------------------------------------

    @Test
    void cleanOtherOnly_logsWarningDeletesOldAndGeneratesFreshKeypair(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        // Pre-create a key pair for a different algorithm ("OldAlgo")
        Path oldKey = tempDir.resolve("worker-OldAlgo.key");
        Path oldPub = tempDir.resolve("worker-OldAlgo.pub");
        Files.write(oldKey, new byte[] {1, 2, 3});
        Files.write(oldPub, new byte[] {4, 5, 6});

        // Construct DefaultWorkerKeyManager configured for Ed25519
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        // Old files must be deleted
        assertThat(oldKey).as("Old algorithm key file must be deleted").doesNotExist();
        assertThat(oldPub).as("Old algorithm pub file must be deleted").doesNotExist();

        // New files for Ed25519 must exist
        assertThat(tempDir.resolve("worker-Ed25519.key"))
                .as("New Ed25519 key file must be generated")
                .exists();
        assertThat(tempDir.resolve("worker-Ed25519.pub"))
                .as("New Ed25519 pub file must be generated")
                .exists();

        // WARNING log must have been emitted (any call with WARN level)
        verify(logger).warn(anyString(), anyString(), anyString());

        // Signal: new registration required
        assertThat(manager.isNewRegistrationRequired())
                .as("isNewRegistrationRequired() must return true after clean-other-only startup")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // AC-D4-MIXED-STATE-REFUSE (E37S03) — startup with other-algo AND configured-algo files
    // -------------------------------------------------------------------------

    @Test
    void mixedState_throwsMixedAlgorithmKeysException(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        // Pre-create key files for BOTH OldAlgo and Ed25519
        Files.write(tempDir.resolve("worker-OldAlgo.key"), new byte[] {1, 2, 3});
        Files.write(tempDir.resolve("worker-OldAlgo.pub"), new byte[] {4, 5, 6});
        // Create a valid Ed25519 keypair to mimic "both algorithms present" scenario
        // (we generate a real Ed25519 key so the existing-key branch can see both)
        {
            // Use a temp sub-manager to generate a real Ed25519 keypair
            Path subDir = Files.createTempDirectory(tempDir, "gen");
            new DefaultWorkerKeyManager(subDir, ED25519, mock(Logger.class));
            Files.copy(subDir.resolve("worker-Ed25519.key"), tempDir.resolve("worker-Ed25519.key"));
            Files.copy(subDir.resolve("worker-Ed25519.pub"), tempDir.resolve("worker-Ed25519.pub"));
        }

        assertThatThrownBy(() -> new DefaultWorkerKeyManager(tempDir, ED25519, logger))
                .isInstanceOf(MixedAlgorithmKeysException.class)
                .satisfies(
                        ex -> {
                            MixedAlgorithmKeysException mex = (MixedAlgorithmKeysException) ex;
                            String message = mex.getMessage();
                            assertThat(message)
                                    .as("Exception message must mention OldAlgo files")
                                    .contains("OldAlgo");
                            assertThat(message)
                                    .as("Exception message must contain remediation hint")
                                    .containsIgnoringCase("remove");
                        });
    }

    // -------------------------------------------------------------------------
    // AC-D4-CONFIGURED-ONLY-NORMAL (E37S03) — startup with only configured-algo files
    // -------------------------------------------------------------------------

    @Test
    void configuredOnly_normalStartupNoWarningNoRotation(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        // First, generate a valid Ed25519 keypair into tempDir
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        // Subsequent load — only Ed25519 files present
        Logger logger2 = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger2);

        // No WARNING should be emitted
        verify(logger2, org.mockito.Mockito.never()).warn(anyString(), anyString(), anyString());

        // isNewRegistrationRequired must be false
        assertThat(manager.isNewRegistrationRequired())
                .as("isNewRegistrationRequired() must be false on normal configured-only startup")
                .isFalse();

        // Public key must load correctly
        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    // -------------------------------------------------------------------------
    // 0600 POSIX permissions on private key file
    // -------------------------------------------------------------------------

    @Test
    void privateKeyFileHas0600Permissions(@TempDir Path tempDir) throws Exception {
        boolean isPosix = Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null;
        assumeThat(isPosix).as("POSIX file attributes not supported on this platform").isTrue();

        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        Path privateKeyFile = tempDir.resolve("worker-Ed25519.key");
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
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        Path privateKeyFile = tempDir.resolve("worker-Ed25519.key");
        byte[] garbageBytes = new byte[] {0x00, 0x01, 0x02, 0x03};
        Files.write(privateKeyFile, garbageBytes);
        long corruptFileTimestamp = Files.getLastModifiedTime(privateKeyFile).toMillis();

        assertThatThrownBy(() -> new DefaultWorkerKeyManager(tempDir, ED25519, logger))
                .isInstanceOf(WorkerKeyCorruptException.class)
                .satisfies(
                        ex -> {
                            WorkerKeyCorruptException wkce = (WorkerKeyCorruptException) ex;
                            assertThat(wkce.getKeyFilePath().toAbsolutePath().toString())
                                    .contains("worker-Ed25519.key");
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
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        byte[] payload = "test-result-payload".getBytes();
        byte[] signature = manager.signResult(payload);

        assertThat(signature).hasSize(64);
    }

    @Test
    void signResultRejectsNullInput(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThatThrownBy(() -> manager.signResult(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Ed25519 determinism
    // -------------------------------------------------------------------------

    @Test
    void signaturesAreDeterministicForSameInput(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

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
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        assertThat(manager.getPublicKeyBytes()).hasSize(32);
    }

    @Test
    void getPublicKeyBytesReturnsCopy(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

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

        new DefaultWorkerKeyManager(nonExistentSubDir, ED25519, logger);

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

            assertThatThrownBy(() -> new DefaultWorkerKeyManager(childDir, ED25519, logger))
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
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        verify(logger).info(anyString(), any(), anyString());
    }

    @Test
    void loadLogsInfoWithFingerprintOnSubsequentRun(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);
        new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        verify(logger, times(2)).info(anyString(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // rotateKeypair
    // -------------------------------------------------------------------------

    @Test
    void rotateKeypairReturnsNewFingerprintDifferentFromOld(@TempDir Path tempDir)
            throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

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
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);

        byte[] originalPubKeyBytes = Files.readAllBytes(tempDir.resolve("worker-Ed25519.pub"));
        manager.rotateKeypair();
        byte[] newPubKeyBytes = Files.readAllBytes(tempDir.resolve("worker-Ed25519.pub"));

        assertThat(newPubKeyBytes)
                .as("Public key file must be updated after rotation")
                .isNotEqualTo(originalPubKeyBytes);

        assertThat(tempDir.resolve("worker-Ed25519.key.new"))
                .as("Temporary .new key file must not exist after successful rotation")
                .doesNotExist();
    }

    @Test
    void rotatedKeypairCanSignAndBeLoaded(@TempDir Path tempDir) throws Exception {
        Logger logger = mock(Logger.class);
        DefaultWorkerKeyManager manager = new DefaultWorkerKeyManager(tempDir, ED25519, logger);
        manager.rotateKeypair();

        byte[] signature = manager.signResult("payload".getBytes());
        assertThat(signature).hasSize(64);

        DefaultWorkerKeyManager reloaded = new DefaultWorkerKeyManager(tempDir, ED25519, logger);
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
