// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code late_result} table.
 *
 * <p>Mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC POJOs at the public
 * surface.
 *
 * <p>DEC-6 first-valid-wins: late results are LOGGED, not discarded. When the first valid result
 * has already been accepted for a packet, all subsequent valid results are persisted here with
 * their full payload and signature for forensic audit.
 *
 * <p>Schema: {@code db/migration/result/V5__create_late_result_table.sql} (DEC-26 Rule 1
 * analogous).
 *
 * <p>Story: E37S09; AC-LATE-RESULT-ENTITY; DEC-6, DEC-35
 */
@Table("late_result")
public class LateResult {

    @Id private Long id;

    /** UUID of the packet that already has a result. NOT NULL. */
    private UUID packetId;

    /** UUID of the worker submitting this late result. NOT NULL. */
    private UUID workerId;

    /**
     * Algorithm used to produce the signature (e.g., {@code "Ed25519"}). NOT NULL. Per DEC-43
     * algorithm-agility: stored for audit even though the result is superseded.
     */
    private String algorithm;

    /**
     * Raw signature bytes submitted by the worker. Variable length, max 8192 bytes per C-19. NOT
     * NULL.
     */
    private byte[] signature;

    /** JSON payload of the result as submitted by the worker. NOT NULL. */
    private String resultPayloadJson;

    /** Timestamp when this late result was received. NOT NULL. */
    private Instant receivedAt;

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getPacketId() {
        return packetId;
    }

    public void setPacketId(UUID packetId) {
        this.packetId = packetId;
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

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public String getResultPayloadJson() {
        return resultPayloadJson;
    }

    public void setResultPayloadJson(String resultPayloadJson) {
        this.resultPayloadJson = resultPayloadJson;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
