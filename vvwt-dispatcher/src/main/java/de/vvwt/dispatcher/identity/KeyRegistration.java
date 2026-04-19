package de.vvwt.dispatcher.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registered Ed25519 public key entry.
 *
 * <p>Represents a worker or submitter key registered via {@code POST /register-key}. A key may be
 * superseded by a newer key (AC3 rotation support); superseded keys remain in the table for audit
 * purposes and continue to be accepted during the grace window.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code keyId} — opaque UUID assigned on registration
 *   <li>{@code role} — {@code "worker"} or {@code "submitter"}
 *   <li>{@code publicKeyBytes} — raw 32-byte Ed25519 public key
 *   <li>{@code registeredAt} — registration timestamp
 *   <li>{@code supersededAt} — set when the key is superseded; null = active
 *   <li>{@code graceExpiresAt} — end of grace window; old key accepted until this time
 *   <li>{@code supersededByKeyId} — UUID of the replacement key; null if not superseded
 *   <li>{@code name} — optional human-readable label
 * </ul>
 *
 * <p>See Story E01S06 AC1–AC4 and DEC-6.
 */
@Entity
@Table(name = "registered_keys")
public class KeyRegistration {

    @Id
    @Column(name = "key_id", nullable = false, updatable = false)
    private UUID keyId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** Raw 32-byte Ed25519 public key (AC1, AC2). */
    @Column(name = "public_key_bytes", nullable = false, updatable = false)
    private byte[] publicKeyBytes;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    /** Null while the key is active; set when the key is superseded (AC3). */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    /**
     * End of the grace window during which the superseded key is still accepted (AC3). Null while
     * the key is active.
     */
    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    /** UUID of the key that superseded this key; null if not superseded (AC3). */
    @Column(name = "superseded_by_key_id")
    private UUID supersededByKeyId;

    /** Optional human-readable label (AC1). */
    @Column(name = "name", length = 255)
    private String name;

    /** JPA no-arg constructor. */
    protected KeyRegistration() {}

    /**
     * Creates a new, active key registration.
     *
     * @param keyId newly generated UUID for this registration
     * @param role {@code "worker"} or {@code "submitter"}
     * @param publicKeyBytes raw 32-byte Ed25519 public key
     * @param registeredAt current timestamp
     * @param name optional human-readable label; may be null
     */
    public KeyRegistration(
            UUID keyId, String role, byte[] publicKeyBytes, Instant registeredAt, String name) {
        this.keyId = keyId;
        this.role = role;
        this.publicKeyBytes = publicKeyBytes;
        this.registeredAt = registeredAt;
        this.name = name;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getKeyId() {
        return keyId;
    }

    public String getRole() {
        return role;
    }

    public byte[] getPublicKeyBytes() {
        return publicKeyBytes;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getGraceExpiresAt() {
        return graceExpiresAt;
    }

    public UUID getSupersededByKeyId() {
        return supersededByKeyId;
    }

    public String getName() {
        return name;
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Marks this key as superseded (AC3 rotation).
     *
     * @param at timestamp when superseded
     * @param graceExpiresAt end of grace window; key is still accepted until this time
     * @param replacedBy the UUID of the replacement key
     */
    public void markSuperseded(Instant at, Instant graceExpiresAt, UUID replacedBy) {
        this.supersededAt = at;
        this.graceExpiresAt = graceExpiresAt;
        this.supersededByKeyId = replacedBy;
    }

    /**
     * Returns true if this key is currently active (not superseded, or still in grace window).
     *
     * @param now current time for grace window check
     */
    public boolean isActiveAt(Instant now) {
        if (supersededAt == null) {
            return true; // never superseded
        }
        // Within grace window: still accepted
        return graceExpiresAt != null && now.isBefore(graceExpiresAt);
    }

    /**
     * Returns true if this key is still valid for signature verification. A superseded key that has
     * not yet passed its grace window is still valid.
     *
     * @param now current time
     */
    public boolean isValidForVerificationAt(Instant now) {
        return isActiveAt(now);
    }
}
