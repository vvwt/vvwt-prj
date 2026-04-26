package de.vvwt.slotopt.worker.identity.internal;

import de.vvwt.slotopt.worker.identity.KeyRotationResult;
import de.vvwt.slotopt.worker.identity.MixedAlgorithmKeysException;
import de.vvwt.slotopt.worker.identity.WorkerKeyCorruptException;
import de.vvwt.slotopt.worker.identity.WorkerKeyGenerationException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Default implementation of {@link WorkerKeyManager}.
 *
 * <p>Manages the per-installation keypair for a worker node. On construction, either generates a
 * new keypair or loads the existing one from the given {@code dataDir}. The keypair files are named
 * {@code worker-{algorithmId}.key} (PKCS#8 DER) and {@code worker-{algorithmId}.pub}
 * (SubjectPublicKeyInfo DER), enabling multi-algorithm coexistence detection at startup.
 *
 * <h2>D-4 Startup Mismatch Mechanic</h2>
 *
 * <p>At construction time, the data directory is scanned for keypair files from other algorithms
 * (files matching {@code worker-*.key}, excluding {@code *.new} temporaries):
 *
 * <ul>
 *   <li><strong>Clean-other-only:</strong> files for another algorithm present, none for configured
 *       algorithm; log WARNING, delete old files, generate fresh keypair, {@link
 *       #isNewRegistrationRequired()} returns {@code true}.
 *   <li><strong>Mixed-state:</strong> files for BOTH the configured algorithm AND another algorithm
 *       present; throw {@link MixedAlgorithmKeysException}; operator must intervene.
 *   <li><strong>Configured-only or fresh:</strong> only configured algorithm files present (or no
 *       files); normal startup; {@link #isNewRegistrationRequired()} returns {@code false}.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances of this class are NOT thread-safe. In particular, {@link #rotateKeypair()} must not
 * be called concurrently. The expected usage pattern is single-threaded initialisation on worker
 * startup.
 *
 * <p>See Stories E01S04, E35S02, E37S03 and DEC-6, DEC-35 (by-analogy: non-Spring interface
 * extraction), DEC-22 (TDD Iron Law).
 */
public final class DefaultWorkerKeyManager implements WorkerKeyManager {

    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String SIGN_ALGORITHM = "Ed25519";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** Raw public key length for Ed25519 (RFC 8032). */
    private static final int RAW_PUBLIC_KEY_LENGTH = 32;

    /** Raw signature length for Ed25519 (RFC 8032). */
    private static final int SIGNATURE_LENGTH = 64;

    /** Number of bytes from the SHA-256 digest used for the fingerprint. */
    private static final int FINGERPRINT_BYTES = 8;

    private final Path dataDir;
    private final String algorithmId;
    private final Path privateKeyPath;
    private final Path publicKeyPath;
    private final String keyNewFilename;
    private final Logger logger;

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private boolean newRegistrationRequired = false;

    /**
     * Constructs a {@code DefaultWorkerKeyManager} for the given data directory and algorithm.
     *
     * <p>On construction, the D-4 mismatch scan runs first, then the manager either generates a new
     * keypair or loads the existing one.
     *
     * @param dataDir the directory in which the keypair files are stored; created with 0700
     *     permissions if it does not exist
     * @param algorithmId the JCE algorithm identifier (e.g., {@code "Ed25519"}); determines the
     *     keypair file names ({@code worker-{algorithmId}.key/pub})
     * @param logger the SLF4J logger to use for INFO-level fingerprint messages and D-4 WARNING
     * @throws MixedAlgorithmKeysException if both configured and other algorithm key files exist
     * @throws WorkerKeyCorruptException if the private key file exists but cannot be parsed
     * @throws WorkerKeyGenerationException if keypair generation fails (entropy starvation, JCE)
     * @throws IOException if {@code dataDir} cannot be created or key files cannot be read/written
     */
    // this-escape: runD4MismatchScan(), loadExisting(), generateAndPersist() are private final
    // methods that only access fields set earlier in this constructor body. No subclass exists —
    // this class is declared final. The warning is a false positive in this single-class
    // composition context (E18S01 / DEC-29).
    @SuppressWarnings("this-escape")
    public DefaultWorkerKeyManager(Path dataDir, String algorithmId, Logger logger)
            throws MixedAlgorithmKeysException, WorkerKeyCorruptException, IOException {
        this.dataDir = dataDir;
        this.algorithmId = algorithmId;
        this.privateKeyPath = dataDir.resolve("worker-" + algorithmId + ".key");
        this.publicKeyPath = dataDir.resolve("worker-" + algorithmId + ".pub");
        this.keyNewFilename = "worker-" + algorithmId + ".key.new";
        this.logger = logger;

        ensureDataDir();
        runD4MismatchScan();

        if (Files.exists(privateKeyPath)) {
            loadExisting();
        } else {
            generateAndPersist();
        }
    }

    // -------------------------------------------------------------------------
    // WorkerKeyManager interface implementation
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public String algorithmId() {
        return algorithmId;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNewRegistrationRequired() {
        return newRegistrationRequired;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ed25519 is deterministic by specification (RFC 8032 §5.1): two calls with the same {@code
     * canonicalResultBytes} will produce identical signature bytes.
     */
    @Override
    public byte[] signResult(byte[] canonicalResultBytes) {
        if (canonicalResultBytes == null) {
            throw new IllegalArgumentException("canonicalResultBytes must not be null");
        }
        try {
            Signature sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initSign(privateKey);
            sig.update(canonicalResultBytes);
            byte[] signature = sig.sign();
            assert signature.length == SIGNATURE_LENGTH
                    : "Ed25519 signature must be exactly 64 bytes, got: " + signature.length;
            return signature;
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign result bytes with Ed25519 key", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The returned array is a fresh copy; mutating it does not affect this manager's state.
     */
    @Override
    public byte[] getPublicKeyBytes() {
        byte[] encoded = publicKey.getEncoded(); // SubjectPublicKeyInfo DER, 44 bytes for Ed25519
        // The raw 32-byte key occupies the last 32 bytes of the DER encoding
        byte[] raw = new byte[RAW_PUBLIC_KEY_LENGTH];
        System.arraycopy(
                encoded, encoded.length - RAW_PUBLIC_KEY_LENGTH, raw, 0, RAW_PUBLIC_KEY_LENGTH);
        return raw;
    }

    /** {@inheritDoc} */
    @Override
    public KeyRotationResult rotateKeypair() throws IOException {
        String oldFingerprint = fingerprint(getPublicKeyBytes());

        KeyPair newKeyPair = generateKeyPair();
        Path newPrivatePath = dataDir.resolve(keyNewFilename);

        // Write new private key to .new file
        Files.write(newPrivatePath, newKeyPair.getPrivate().getEncoded());
        setPrivateKeyPermissions(newPrivatePath);

        // Update public key file
        Files.write(publicKeyPath, newKeyPair.getPublic().getEncoded());

        // Atomically replace old private key with the new one
        try {
            Files.move(
                    newPrivatePath,
                    privateKeyPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Windows antivirus lock or FS limitation — fall back to non-atomic replace
            logger.warn(
                    "ATOMIC_MOVE not supported on this platform; using non-atomic replace for key"
                            + " rotation. This is safe on single-threaded startup. File: {}",
                    privateKeyPath,
                    e);
            Files.move(newPrivatePath, privateKeyPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Update in-memory references
        this.privateKey = newKeyPair.getPrivate();
        this.publicKey = newKeyPair.getPublic();

        String newFingerprint = fingerprint(getPublicKeyBytes());
        return new KeyRotationResult(oldFingerprint, newFingerprint);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the data directory exists with 0700 (POSIX) or equivalent ACL permissions.
     *
     * @throws IOException if the directory cannot be created
     */
    private void ensureDataDir() throws IOException {
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
            setDirectoryPermissions(dataDir);
        }
    }

    /**
     * D-4 mismatch scan: checks the data directory for keypair files from algorithms other than the
     * configured one.
     *
     * <p>Key file pattern: {@code worker-*.key} (excluding {@code *.new} temporaries).
     *
     * <ul>
     *   <li>Clean-other-only: only other-algorithm files present — log WARN, delete, set {@code
     *       newRegistrationRequired = true}.
     *   <li>Mixed-state: other-algorithm files AND configured-algorithm files — throw {@link
     *       MixedAlgorithmKeysException}.
     *   <li>Configured-only or none: return without action.
     * </ul>
     *
     * @throws MixedAlgorithmKeysException if both configured and other algorithm key files exist
     * @throws IOException if the directory cannot be listed or files cannot be deleted
     */
    private void runD4MismatchScan() throws MixedAlgorithmKeysException, IOException {
        if (!Files.isDirectory(dataDir)) {
            return; // directory created by ensureDataDir() — may be empty now
        }

        List<Path> otherAlgoKeyFiles = new ArrayList<>();
        List<Path> otherAlgoFiles = new ArrayList<>();

        String configuredKeyName = "worker-" + algorithmId + ".key";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "worker-*.key")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.endsWith(".new")) {
                    continue; // skip temporary rotation files
                }
                if (!fileName.equals(configuredKeyName)) {
                    otherAlgoKeyFiles.add(entry);
                    otherAlgoFiles.add(entry);
                    // Add the corresponding .pub file if it exists
                    String pubFileName = fileName.replace(".key", ".pub");
                    Path pubPath = dataDir.resolve(pubFileName);
                    if (Files.exists(pubPath)) {
                        otherAlgoFiles.add(pubPath);
                    }
                }
            }
        }

        if (otherAlgoFiles.isEmpty()) {
            return; // configured-only or fresh
        }

        boolean configuredFilesExist = Files.exists(privateKeyPath);

        if (configuredFilesExist) {
            // Mixed-state: both configured and other algorithm files present
            List<Path> allConflictingFiles = new ArrayList<>(otherAlgoFiles);
            allConflictingFiles.add(privateKeyPath);
            if (Files.exists(publicKeyPath)) {
                allConflictingFiles.add(publicKeyPath);
            }
            throw new MixedAlgorithmKeysException(allConflictingFiles);
        }

        // Clean-other-only: only other algorithm files, none for the configured algorithm
        StringBuilder fileListMsg = new StringBuilder();
        for (Path f : otherAlgoFiles) {
            fileListMsg.append("\n    ").append(f.toAbsolutePath());
        }
        logger.warn(
                "D-4: Found keypair files for a different algorithm; deleting and generating fresh"
                        + " keypair for configured algorithm '{}'{}",
                algorithmId,
                fileListMsg.toString());

        for (Path oldFile : otherAlgoFiles) {
            Files.deleteIfExists(oldFile);
        }

        newRegistrationRequired = true;
        // Falls through to generateAndPersist() in the constructor
    }

    /**
     * Generates a new Ed25519 keypair and persists it to disk.
     *
     * @throws WorkerKeyGenerationException if keypair generation fails
     * @throws IOException if files cannot be written
     */
    private void generateAndPersist() throws IOException {
        KeyPair keyPair = generateKeyPair();
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();

        // Write private key (PKCS#8 DER format)
        Files.write(privateKeyPath, privateKey.getEncoded());
        setPrivateKeyPermissions(privateKeyPath);

        // Write public key (SubjectPublicKeyInfo DER format)
        Files.write(publicKeyPath, publicKey.getEncoded());

        String fp = fingerprint(getPublicKeyBytes());
        logger.info(
                "generated new worker keypair at {} (public key fingerprint: {})",
                privateKeyPath.toAbsolutePath(),
                fp);
    }

    /**
     * Loads an existing keypair from disk.
     *
     * @throws WorkerKeyCorruptException if the private key file cannot be parsed
     * @throws IOException if files cannot be read
     */
    private void loadExisting() throws WorkerKeyCorruptException, IOException {
        byte[] privateKeyBytes = Files.readAllBytes(privateKeyPath);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new WorkerKeyCorruptException(privateKeyPath, e);
        }

        byte[] publicKeyBytes = Files.readAllBytes(publicKeyPath);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new WorkerKeyCorruptException(publicKeyPath, e);
        }

        String fp = fingerprint(getPublicKeyBytes());
        logger.info(
                "loaded worker keypair from {} (public key fingerprint: {})",
                privateKeyPath.toAbsolutePath(),
                fp);
    }

    /**
     * Generates a fresh Ed25519 keypair using the JDK native provider (available since JDK 15).
     *
     * @return the generated keypair
     * @throws WorkerKeyGenerationException if generation fails due to entropy starvation or JCE
     *     policy
     */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new WorkerKeyGenerationException(
                    "Ed25519 is not available in this JVM — JDK 21 is required (DEC-10). "
                            + "Algorithm: "
                            + KEY_ALGORITHM,
                    e);
        } catch (Exception e) {
            throw new WorkerKeyGenerationException(
                    "Keypair generation failed — possible entropy starvation or JCE policy"
                            + " restriction",
                    e);
        }
    }

    /**
     * Sets the private key file to owner-read/write only (0600 on POSIX, equivalent ACL on
     * Windows).
     *
     * @param path the private key file path
     * @throws IOException if permissions cannot be set
     */
    private void setPrivateKeyPermissions(Path path) throws IOException {
        setPosixOrAclPermissions(path, true);
    }

    /**
     * Sets the data directory to owner-only access (0700 on POSIX, equivalent ACL on Windows).
     *
     * @param path the directory path
     * @throws IOException if permissions cannot be set
     */
    private void setDirectoryPermissions(Path path) throws IOException {
        setPosixOrAclPermissions(path, false);
    }

    /**
     * Sets file or directory permissions to owner-only on POSIX or Windows.
     *
     * <p>On POSIX systems: sets {@code rw-------} for files, {@code rwx------} for directories. On
     * Windows (ACL): grants the current owner Read, Write, Execute on the file/directory only. If
     * neither attribute view is supported, logs a warning and leaves platform defaults.
     *
     * @param path the path to apply permissions to
     * @param isPrivateFile {@code true} if this is a private key file (0600), {@code false} for a
     *     directory (0700)
     * @throws IOException if setting permissions fails
     */
    private void setPosixOrAclPermissions(Path path, boolean isPrivateFile) throws IOException {
        PosixFileAttributeView posixView =
                Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posixView != null) {
            Set<PosixFilePermission> permissions =
                    isPrivateFile
                            ? PosixFilePermissions.fromString("rw-------")
                            : PosixFilePermissions.fromString("rwx------");
            posixView.setPermissions(permissions);
            return;
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (aclView != null) {
            // On Windows: clear existing ACLs and set owner-only permissions
            java.nio.file.attribute.UserPrincipal owner = Files.getOwner(path);
            Set<AclEntryPermission> filePermissions =
                    isPrivateFile
                            ? EnumSet.of(
                                    AclEntryPermission.READ_DATA,
                                    AclEntryPermission.WRITE_DATA,
                                    AclEntryPermission.READ_ATTRIBUTES,
                                    AclEntryPermission.WRITE_ATTRIBUTES,
                                    AclEntryPermission.READ_NAMED_ATTRS,
                                    AclEntryPermission.WRITE_NAMED_ATTRS,
                                    AclEntryPermission.READ_ACL,
                                    AclEntryPermission.SYNCHRONIZE)
                            : EnumSet.of(
                                    AclEntryPermission.READ_DATA,
                                    AclEntryPermission.WRITE_DATA,
                                    AclEntryPermission.EXECUTE,
                                    AclEntryPermission.READ_ATTRIBUTES,
                                    AclEntryPermission.WRITE_ATTRIBUTES,
                                    AclEntryPermission.READ_NAMED_ATTRS,
                                    AclEntryPermission.WRITE_NAMED_ATTRS,
                                    AclEntryPermission.READ_ACL,
                                    AclEntryPermission.SYNCHRONIZE);
            AclEntry ownerEntry =
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(filePermissions)
                            .build();
            aclView.setAcl(List.of(ownerEntry));
            return;
        }

        logger.warn(
                "Neither POSIX nor ACL file attribute views are supported on this platform. "
                        + "Skipping permission hardening for: {}. Review manually.",
                path);
    }

    /**
     * Computes a short fingerprint for a raw 32-byte Ed25519 public key.
     *
     * <p>The fingerprint is the first 8 bytes of the SHA-256 digest, encoded as lowercase hex. This
     * is sufficient for ops correlation without exposing any key material.
     *
     * @param rawPublicKeyBytes the 32-byte raw Ed25519 public key
     * @return a 16-character lowercase hex string (8 bytes = 16 hex chars)
     */
    static String fingerprint(byte[] rawPublicKeyBytes) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(rawPublicKeyBytes);
            byte[] first8 = new byte[FINGERPRINT_BYTES];
            System.arraycopy(digest, 0, first8, 0, FINGERPRINT_BYTES);
            return HexFormat.of().formatHex(first8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated to be present in every Java SE implementation (JDK docs)
            throw new IllegalStateException(
                    "SHA-256 not available — this should never happen on a compliant JDK", e);
        }
    }
}
