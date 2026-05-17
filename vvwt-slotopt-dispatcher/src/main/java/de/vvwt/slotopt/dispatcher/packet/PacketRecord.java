// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code packet} table.
 *
 * <p>This is a mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC POJOs at
 * the public surface. Records would require wither-pattern propagation through all mutation sites.
 *
 * <p>Schema per AC-PACKET-RECORD-ENTITY (E37S08). Fields:
 *
 * <ul>
 *   <li>{@code id} — auto-generated surrogate PK (BIGINT GENERATED ALWAYS AS IDENTITY)
 *   <li>{@code packetId} — externally-visible UUID, UNIQUE constraint
 *   <li>{@code jobId} — UUID of the owning job (FK semantics)
 *   <li>{@code packetPayloadJson} — JSON payload containing the packet rank interval + job def
 *   <li>{@code status} — lifecycle status: {@code UNCLAIMED}, {@code CLAIMED}, {@code
 *       RESULT_RECEIVED}, {@code TIMEDOUT}
 *   <li>{@code claimedByWorkerId} — UUID of the claiming worker (nullable)
 *   <li>{@code claimedAt} — timestamp when claimed (nullable)
 *   <li>{@code timeoutAt} — deadline for claimed packet (nullable)
 * </ul>
 *
 * <p>Story: E37S08; AC-PACKET-RECORD-ENTITY; DEC-9, DEC-35
 */
@Table("packet")
public class PacketRecord {

    @Id private Long id;

    /** Externally-visible packet identifier. UNIQUE per DB constraint. NOT NULL. */
    private UUID packetId;

    /** UUID of the owning job. NOT NULL. */
    private UUID jobId;

    /**
     * JSON payload containing the rank interval and canonicalized job definition for this packet.
     * NOT NULL.
     */
    private String packetPayloadJson;

    /**
     * Lifecycle status. Values: {@code UNCLAIMED}, {@code CLAIMED}, {@code RESULT_RECEIVED}, {@code
     * TIMEDOUT}. NOT NULL.
     */
    private String status;

    /** UUID of the worker that claimed this packet. Nullable — null when UNCLAIMED or TIMEDOUT. */
    private UUID claimedByWorkerId;

    /** Timestamp when this packet was claimed. Nullable — null when UNCLAIMED or TIMEDOUT. */
    private Instant claimedAt;

    /** Deadline for a claimed packet. Nullable — null when UNCLAIMED or TIMEDOUT. */
    private Instant timeoutAt;

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

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getPacketPayloadJson() {
        return packetPayloadJson;
    }

    public void setPacketPayloadJson(String packetPayloadJson) {
        this.packetPayloadJson = packetPayloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getClaimedByWorkerId() {
        return claimedByWorkerId;
    }

    public void setClaimedByWorkerId(UUID claimedByWorkerId) {
        this.claimedByWorkerId = claimedByWorkerId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(Instant timeoutAt) {
        this.timeoutAt = timeoutAt;
    }
}
