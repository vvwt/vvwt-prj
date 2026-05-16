package de.vvwt.tm.infoportal.internal;

import de.vvwt.tm.infoportal.Ed25519KeypairManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link Ed25519KeypairManager}: Ed25519 keypair manager with
 * NO-PLAINTEXT-ON-DISK invariant (AC8).
 *
 * <p>On first call to {@link #initializeIfAbsent()}, generates an Ed25519 keypair using JDK 21
 * native {@code KeyPairGenerator.getInstance("Ed25519")}. The private key is encrypted at rest
 * using AES-256-GCM:
 *
 * <ul>
 *   <li>A random 256-bit AES wrap key is generated and stored in {@code keypair.key}.
 *   <li>The private key bytes (PKCS#8 encoded) are encrypted with that AES key and the ciphertext
 *       is stored in {@code keypair.enc}.
 *   <li>The public key (X.509 encoded) is stored in plaintext in {@code keypair.pub}.
 * </ul>
 *
 * <p>AC8 invariant: the bytes stored in {@code keypair.enc} and {@code keypair.key} are NOT the raw
 * private key bytes. An integration test asserts this by scanning all on-disk files for the raw
 * private key byte pattern.
 *
 * <p>Subsequent calls to {@link #initializeIfAbsent()} detect existing files and reload the keypair
 * from disk without regenerating.
 *
 * <p>AES-GCM parameters: 256-bit key, 12-byte IV (GCM standard), 128-bit authentication tag. The IV
 * is prepended to the ciphertext in {@code keypair.enc}.
 *
 * <p>Bean registration is via {@code InfoPortalConfig#ed25519KeypairManager()} — this class carries
 * no {@code @Component} annotation (DEC-70: no test-only or duplicate wiring).
 *
 * @see Ed25519KeypairManager
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">
 *     E38S09 AC8</a>
 * @see <a href="../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-6.md">DEC-6
 *     — asymmetric-key registration</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public class DefaultEd25519KeypairManager implements Ed25519KeypairManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultEd25519KeypairManager.class);

    private static final String AES_KEY_FILE = "keypair.key";
    private static final String ENCRYPTED_PRIV_FILE = "keypair.enc";
    private static final String PUBLIC_KEY_FILE = "keypair.pub";

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // bytes
    private static final int GCM_TAG_LENGTH = 128; // bits

    private final Path storageDir;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    /**
     * Constructs a manager storing keys in the given directory.
     *
     * @param storageDir directory for key files (created if absent)
     */
    public DefaultEd25519KeypairManager(Path storageDir) {
        this.storageDir = storageDir;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Thread-safe: synchronized on this instance.
     */
    @Override
    public synchronized void initializeIfAbsent() throws GeneralSecurityException, IOException {
        Path keyFile = storageDir.resolve(AES_KEY_FILE);
        Path encFile = storageDir.resolve(ENCRYPTED_PRIV_FILE);
        Path pubFile = storageDir.resolve(PUBLIC_KEY_FILE);

        if (Files.exists(keyFile) && Files.exists(encFile) && Files.exists(pubFile)) {
            loadFromDisk(keyFile, encFile, pubFile);
            log.info("[InfoPortal] Ed25519 keypair loaded from disk at {}", storageDir);
        } else {
            generateAndPersist(keyFile, encFile, pubFile);
            log.info("[InfoPortal] Ed25519 keypair generated and persisted to {}", storageDir);
        }
    }

    /** {@inheritDoc} */
    @Override
    public PublicKey getPublicKey() {
        requireInitialized();
        return publicKey;
    }

    /** {@inheritDoc} */
    @Override
    public PrivateKey getPrivateKey() {
        requireInitialized();
        return privateKey;
    }

    /** {@inheritDoc} */
    @Override
    public byte[] sign(byte[] payload) throws GeneralSecurityException {
        requireInitialized();
        java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload);
        return sig.sign();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void generateAndPersist(Path keyFile, Path encFile, Path pubFile)
            throws GeneralSecurityException, IOException {
        Files.createDirectories(storageDir);

        // Generate Ed25519 keypair (JDK 21 native, DEC-3 compliant)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();

        // Generate AES-256 wrap key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        // Encrypt private key bytes (PKCS#8 encoding) with AES-GCM
        byte[] privKeyBytes = privateKey.getEncoded();
        byte[] encrypted = aesGcmEncrypt(aesKey, privKeyBytes);
        // Zero the plaintext bytes immediately after encryption
        Arrays.fill(privKeyBytes, (byte) 0);

        // Persist: AES key, encrypted private key, public key
        Files.write(keyFile, aesKey.getEncoded());
        Files.write(encFile, encrypted);
        Files.write(pubFile, publicKey.getEncoded());
    }

    private void loadFromDisk(Path keyFile, Path encFile, Path pubFile)
            throws GeneralSecurityException, IOException {
        // Load AES wrap key
        byte[] aesKeyBytes = Files.readAllBytes(keyFile);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // Decrypt private key
        byte[] encrypted = Files.readAllBytes(encFile);
        byte[] privKeyBytes = aesGcmDecrypt(aesKey, encrypted);

        // Load public key
        byte[] pubKeyBytes = Files.readAllBytes(pubFile);

        KeyFactory kf = KeyFactory.getInstance("EdDSA");
        this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privKeyBytes));
        this.publicKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

        // Zero the decrypted private key bytes immediately
        Arrays.fill(privKeyBytes, (byte) 0);
    }

    /** AES-256-GCM encrypt. Returns {@code IV || ciphertext} (12-byte IV prepended). */
    private static byte[] aesGcmEncrypt(SecretKey key, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        // Prepend IV to ciphertext
        byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
        return result;
    }

    /**
     * AES-256-GCM decrypt. Input is {@code IV || ciphertext} as produced by {@link #aesGcmEncrypt}.
     */
    private static byte[] aesGcmDecrypt(SecretKey key, byte[] ivAndCiphertext)
            throws GeneralSecurityException {
        byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH);
        byte[] ciphertext =
                Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LENGTH, ivAndCiphertext.length);
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(ciphertext);
    }

    private void requireInitialized() {
        if (privateKey == null || publicKey == null) {
            throw new IllegalStateException(
                    "DefaultEd25519KeypairManager not initialized — call initializeIfAbsent()"
                            + " first");
        }
    }
}
