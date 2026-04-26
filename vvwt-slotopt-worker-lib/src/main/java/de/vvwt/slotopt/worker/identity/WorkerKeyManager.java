package de.vvwt.slotopt.worker.identity;

import java.io.IOException;

/**
 * Manages the per-installation keypair for a worker node.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>On first invocation in a given data directory, generates a keypair using the configured
 *       algorithm and persists it as {@code worker-{algorithmId}.key} (PKCS#8 DER) and {@code
 *       worker-{algorithmId}.pub} (SubjectPublicKeyInfo DER) with POSIX 0600 / 0700 permissions
 *       (Windows ACL equivalent).
 *   <li>On subsequent invocations, loads the existing keypair. If the private key file exists but
 *       is unparseable, throws {@link WorkerKeyCorruptException} — no auto-recovery.
 * </ol>
 *
 * <h2>D-4 Startup Mismatch Mechanic</h2>
 *
 * <p>At startup, the manager scans the data directory for keypair files from other algorithms:
 *
 * <ul>
 *   <li>If files exist for OTHER algorithms only (none for the configured algorithm): logs WARNING,
 *       deletes old files, generates a fresh keypair, sets {@link #isNewRegistrationRequired()} to
 *       {@code true}.
 *   <li>If files exist for BOTH the configured algorithm AND another algorithm (mixed state):
 *       throws {@link MixedAlgorithmKeysException} — operator must manually clean up.
 *   <li>If files exist only for the configured algorithm (or no files): normal startup.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations of this interface are NOT thread-safe. In particular, {@link #rotateKeypair()}
 * must not be called concurrently. The expected usage pattern is single-threaded initialisation on
 * worker startup.
 *
 * <p>The canonical implementation is {@link
 * de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager}.
 *
 * <p>See Story E01S04, E35S02, E37S03, and DEC-6, DEC-35 (by-analogy).
 */
public interface WorkerKeyManager {

    /**
     * Returns the signature algorithm identifier for this worker's keypair.
     *
     * <p>The identifier is the JCE/JDK canonical name for the algorithm (e.g., {@code "Ed25519"}).
     * V1 always returns {@code "Ed25519"}. Future PQC-capable workers may return {@code
     * "ML-DSA-65"} or similar.
     *
     * @return the algorithm identifier string; never {@code null}
     */
    String algorithmId();

    /**
     * Returns {@code true} if this manager was initialized via a clean-other-only startup (D-4
     * mechanic: old algorithm files were found and deleted, a new keypair was generated). The
     * caller MUST re-register with the dispatcher before sending any signed results.
     *
     * <p>Returns {@code false} on normal startup (configured-only or fresh keypair generation).
     *
     * @return {@code true} if re-registration with the dispatcher is required
     */
    boolean isNewRegistrationRequired();

    /**
     * Signs the given canonical result bytes using the worker's Ed25519 private key.
     *
     * <p>Ed25519 is deterministic by specification (RFC 8032 §5.1): two calls with the same {@code
     * canonicalResultBytes} will produce identical signature bytes.
     *
     * @param canonicalResultBytes the byte array to sign; must not be {@code null}
     * @return a 64-byte raw Ed25519 detached signature
     * @throws IllegalArgumentException if {@code canonicalResultBytes} is {@code null}
     * @throws IllegalStateException if signing fails due to an internal JCE error
     */
    byte[] signResult(byte[] canonicalResultBytes);

    /**
     * Returns the raw 32-byte Ed25519 public key for use in registration with the dispatcher.
     *
     * <p>The returned array is a fresh copy; mutating it does not affect this manager's state.
     *
     * @return a 32-byte array containing the raw Ed25519 public key (RFC 8032 encoding)
     */
    byte[] getPublicKeyBytes();

    /**
     * Rotates the worker keypair atomically.
     *
     * <ol>
     *   <li>Generates a new Ed25519 keypair.
     *   <li>Writes the new private key to a temporary file.
     *   <li>Updates the public key file.
     *   <li>Atomically replaces the old private key file (POSIX {@code rename(2)} semantics via
     *       {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}). On Windows, if {@code
     *       ATOMIC_MOVE} is not supported, falls back to {@code REPLACE_EXISTING} only
     *       (non-atomic).
     *   <li>Updates this manager's internal key references.
     * </ol>
     *
     * <p><strong>Caller responsibility:</strong> after rotation, the caller must re-register the
     * new public key with the dispatcher via the {@code register-key} endpoint, passing the old
     * fingerprint as the {@code supersedes} field.
     *
     * <p><strong>Thread safety:</strong> this method is NOT thread-safe. Do not call concurrently.
     *
     * @return a {@link KeyRotationResult} containing the old and new public-key fingerprints
     * @throws WorkerKeyGenerationException if the new keypair cannot be generated
     * @throws IOException if writing or moving key files fails
     */
    KeyRotationResult rotateKeypair() throws IOException;
}
