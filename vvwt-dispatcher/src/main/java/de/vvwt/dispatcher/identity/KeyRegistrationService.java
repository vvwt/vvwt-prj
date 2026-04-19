package de.vvwt.dispatcher.identity;

import de.vvwt.dispatcher.crypto.Ed25519Verifier;
import de.vvwt.dispatcher.crypto.InvalidSignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for {@code POST /register-key}.
 *
 * <h2>Happy path</h2>
 *
 * <ol>
 *   <li>Validate {@code role} ({@code "worker"} or {@code "submitter"})
 *   <li>Decode and validate the Base64 {@code publicKey} field (must be exactly 32 bytes and
 *       parseable as Ed25519 — AC2)
 *   <li>Check for existing registration:
 *       <ul>
 *         <li>Same bytes + same role → idempotent, return existing keyId (AC4)
 *         <li>Same bytes + different role → {@link RoleConflictException} → 409 (AC4)
 *         <li>No match → proceed to registration
 *       </ul>
 *   <li>If {@code supersedes} is present:
 *       <ul>
 *         <li>Decode and find the matching key by raw bytes + same role
 *         <li>Mark it superseded with {@code supersededAt = now} and {@code graceExpiresAt = now +
 *             dispatcher.packet.timeout × 2} (AC3)
 *       </ul>
 *   <li>Insert new {@link KeyRegistration} row; return keyId + role + registeredAt
 * </ol>
 *
 * <p>The grace window default is {@code dispatcher.packet.timeout × 2} minutes. This is the SHARED
 * timeout config also used by E01S07 — do NOT introduce a separate grace-window knob (AC3
 * explicitly forbids two independent timeout knobs).
 *
 * <p>See Story E01S06 AC1–AC4, AC12 and DEC-6.
 */
@Service
public class KeyRegistrationService {

    /** Shared packet-timeout property used by both E01S06 (grace window) and E01S07. */
    @Value("${dispatcher.packet.timeout:5}")
    private long packetTimeoutMinutes;

    private final KeyRegistrationRepository repository;

    public KeyRegistrationService(KeyRegistrationRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers a public key or returns the existing registration if the key is already known.
     *
     * @param request the parsed register-key request
     * @return registration result containing keyId, role, and registeredAt
     * @throws IllegalArgumentException if the role is invalid, the publicKey field is null/blank,
     *     or the decoded key bytes fail Ed25519 validation (AC2)
     * @throws RoleConflictException if the same key bytes exist but with a different role (AC4)
     */
    @Transactional
    public RegisterKeyResponse register(RegisterKeyRequest request) {
        // --- Validate role ---
        String role = request.role();
        if (role == null || (!role.equals("worker") && !role.equals("submitter"))) {
            throw new IllegalArgumentException(
                    "role must be 'worker' or 'submitter', got: " + role);
        }

        // --- Decode and validate public key (AC2) ---
        byte[] publicKeyBytes = decodeAndValidateKey(request.publicKey());

        // --- Idempotency check (AC4) ---
        List<KeyRegistration> existing = repository.findAllByPublicKeyBytes(publicKeyBytes);
        for (KeyRegistration registration : existing) {
            if (registration.getRole().equals(role)) {
                // Idempotent: same key + same role → return existing keyId
                return new RegisterKeyResponse(
                        registration.getKeyId(),
                        registration.getRole(),
                        registration.getRegisteredAt());
            } else {
                // Role conflict: same key + different role → 409
                throw new RoleConflictException(
                        "Public key is already registered with role '"
                                + registration.getRole()
                                + "'; cannot register as '"
                                + role
                                + "'");
            }
        }

        // --- Handle key rotation (AC3) ---
        if (request.supersedes() != null && !request.supersedes().isBlank()) {
            byte[] supersededKeyBytes = decodeBase64(request.supersedes());
            Optional<KeyRegistration> supersededOpt =
                    repository.findByPublicKeyBytesAndRole(supersededKeyBytes, role);
            if (supersededOpt.isPresent()) {
                KeyRegistration superseded = supersededOpt.get();
                Instant now = Instant.now();
                Duration graceWindow = Duration.ofMinutes(packetTimeoutMinutes * 2);
                Instant graceExpiresAt = now.plus(graceWindow);
                // The new key UUID is generated below — assign a placeholder to avoid forward ref
                UUID newKeyId = UUID.randomUUID();
                superseded.markSuperseded(now, graceExpiresAt, newKeyId);
                repository.save(superseded);

                // Insert new registration
                Instant registeredAt = now;
                KeyRegistration newRegistration =
                        new KeyRegistration(
                                newKeyId, role, publicKeyBytes, registeredAt, request.name());
                repository.save(newRegistration);
                return new RegisterKeyResponse(newKeyId, role, registeredAt);
            }
            // Supersedes key not found — treat as new registration (no error per AC3 wording)
        }

        // --- New registration ---
        UUID keyId = UUID.randomUUID();
        Instant registeredAt = Instant.now();
        KeyRegistration registration =
                new KeyRegistration(keyId, role, publicKeyBytes, registeredAt, request.name());
        repository.save(registration);
        return new RegisterKeyResponse(keyId, role, registeredAt);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Decodes a Base64 public key string and validates it as a 32-byte Ed25519 key.
     *
     * @param base64PublicKey the Base64-encoded public key string
     * @return decoded 32-byte key bytes
     * @throws IllegalArgumentException if the string is null/blank, not valid Base64, or the
     *     decoded bytes are not a valid Ed25519 key
     */
    private static byte[] decodeAndValidateKey(String base64PublicKey) {
        if (base64PublicKey == null || base64PublicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey must not be null or blank");
        }
        byte[] keyBytes = decodeBase64(base64PublicKey);

        // Validate length and parseability as Ed25519 (AC2)
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "invalid public key: expected 32 bytes, got " + keyBytes.length);
        }
        try {
            Ed25519Verifier.decodePublicKey(keyBytes);
        } catch (InvalidSignatureException parseFailure) {
            throw new IllegalArgumentException(
                    "invalid public key: not a valid Ed25519 key — " + parseFailure.getMessage());
        }
        return keyBytes;
    }

    /**
     * Decodes a standard Base64 string to bytes.
     *
     * @throws IllegalArgumentException if the string is not valid Base64
     */
    private static byte[] decodeBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid Base64 encoding: " + e.getMessage(), e);
        }
    }
}
