package de.vvwt.slotopt.worker.identity;

/**
 * Result of a {@link WorkerKeyManager#rotateKeypair()} operation.
 *
 * <p>Both fingerprints are the first 8 bytes of the SHA-256 digest of the respective raw 32-byte
 * Ed25519 public key, encoded as a lowercase hexadecimal string without separators.
 *
 * <p>The caller is responsible for re-registering the new public key with the dispatcher via the
 * {@code register-key} endpoint, passing the old fingerprint as the {@code supersedes} field. See
 * Story E01S04 AC10 and DEC-6.
 *
 * @param oldFingerprint hex fingerprint of the public key that was active before rotation
 * @param newFingerprint hex fingerprint of the public key that is now active
 */
public record KeyRotationResult(String oldFingerprint, String newFingerprint) {}
