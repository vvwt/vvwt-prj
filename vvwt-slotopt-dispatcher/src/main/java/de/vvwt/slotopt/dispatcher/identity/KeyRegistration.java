// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code key_registration} table.
 *
 * <p>This is a mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC POJOs at
 * the public surface. Records would require wither-pattern propagation through all mutation sites.
 *
 * <p>Schema per AC-KEY-REGISTRATION-ENTITY (E37S05) + C-12 lock: single row per {@code worker_id}
 * (UNIQUE constraint). {@code publicKeyBytes} is VARBINARY(8192) per C-19 to accommodate future PQC
 * public key sizes.
 *
 * <p>Role values are the concrete strings from E37S02 spec section (b): {@code "worker"} and {@code
 * "submitter"}.
 *
 * <p>Story: E37S05; Spec: E37S02 spec (b), (d); DEC-6, DEC-35
 */
@Table("key_registration")
public class KeyRegistration {

    @Id private Long id;

    private UUID workerId;

    /**
     * Canonical algorithm identifier (e.g., {@code "Ed25519"}) per DEC-43. NOT NULL, VARCHAR 32.
     */
    private String algorithm;

    /** Raw public key bytes. Variable length, max 8192 bytes per C-19. */
    private byte[] publicKeyBytes;

    /** Role of the registrant: {@code "worker"} or {@code "submitter"} per spec (b). NOT NULL. */
    private String role;

    /** Timestamp of registration. NOT NULL. */
    private Instant registeredAt;

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public void setWorkerId(UUID workerId) {
        this.workerId = workerId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public byte[] getPublicKeyBytes() {
        return publicKeyBytes;
    }

    public void setPublicKeyBytes(byte[] publicKeyBytes) {
        this.publicKeyBytes = publicKeyBytes;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }
}
