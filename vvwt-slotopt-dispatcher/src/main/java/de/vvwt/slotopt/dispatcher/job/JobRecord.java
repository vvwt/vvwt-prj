// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code job} table.
 *
 * <p>This is a mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC POJOs at
 * the public surface. Records would require wither-pattern propagation through all mutation sites.
 *
 * <p>Schema per AC-JOB-RECORD-ENTITY (E37S07). Fields:
 *
 * <ul>
 *   <li>{@code id} — auto-generated surrogate PK (BIGINT GENERATED ALWAYS AS IDENTITY)
 *   <li>{@code jobId} — externally-visible UUID, UNIQUE constraint
 *   <li>{@code submittedAt} — timestamp of initial job submission
 *   <li>{@code jobDefJson} — canonicalized JSON serialization of the {@link
 *       de.vvwt.slotopt.worker.types.JobDef}
 *   <li>{@code status} — job lifecycle status: {@code RECEIVED}, {@code DECOMPOSED}, {@code
 *       COMPLETED}
 * </ul>
 *
 * <p>DEC-9: The {@code jobDefJson} field stores the serialized {@link
 * de.vvwt.slotopt.worker.types.JobDef} which contains the {@link
 * de.vvwt.slotopt.worker.types.CanonicalPhaseDef} (no UUIDs — DEC-9 boundary is enforced by the
 * {@link RawPhaseDefDeserializer} at wire ingestion time).
 *
 * <p>Story: E37S07; AC-JOB-RECORD-ENTITY; DEC-9, DEC-35
 */
@Table("job")
public class JobRecord {

    @Id private Long id;

    /** Externally-visible job identifier. UNIQUE per DB constraint. NOT NULL. */
    private UUID jobId;

    /** Timestamp of initial job submission. NOT NULL. */
    private Instant submittedAt;

    /**
     * Canonicalized JSON of the {@link de.vvwt.slotopt.worker.types.JobDef} submitted with this
     * job. Stored for audit and cache-key derivation. NOT NULL.
     */
    private String jobDefJson;

    /**
     * Lifecycle status. Values: {@code RECEIVED}, {@code DECOMPOSED}, {@code COMPLETED}. NOT NULL.
     */
    private String status;

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getJobDefJson() {
        return jobDefJson;
    }

    public void setJobDefJson(String jobDefJson) {
        this.jobDefJson = jobDefJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
