package de.vvwt.dispatcher.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of an accepted optimization job (AC10 of E01S06).
 *
 * <p>Created on cache miss — the job is queued for decomposition by E01S07.
 * Cache-hit submissions do NOT create a job row (AC9).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code jobId} — UUID assigned at intake</li>
 *   <li>{@code submitterKeyId} — the registered key that signed the submission</li>
 *   <li>{@code phaseId} — from {@code phaseDef.phaseId} (audit-only, per DEC-9)</li>
 *   <li>{@code rawPhaseDefJson} — the original {@code phaseDef} JSON text as received</li>
 *   <li>{@code canonicalPhaseDefJson} — JSON representation of the {@link de.vvwt.worker.types.CanonicalPhaseDef}</li>
 *   <li>{@code fingerprint} — 32-byte SHA-256 of the canonical form</li>
 *   <li>{@code status} — {@code "queued"} on creation; updated by E01S07</li>
 *   <li>{@code submittedAt} — intake timestamp</li>
 * </ul>
 *
 * <p>See Story E01S06 AC5, AC9, AC10 and DEC-9.
 */
@Entity
@Table(name = "jobs")
public class JobRecord {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "submitter_key_id", nullable = false, updatable = false)
    private UUID submitterKeyId;

    @Column(name = "phase_id", nullable = false, updatable = false)
    private int phaseId;

    @Column(name = "raw_phase_def_json", nullable = false, updatable = false,
            columnDefinition = "TEXT")
    private String rawPhaseDefJson;

    @Column(name = "canonical_phase_def_json", nullable = false, updatable = false,
            columnDefinition = "TEXT")
    private String canonicalPhaseDefJson;

    @Column(name = "fingerprint", nullable = false, updatable = false)
    private byte[] fingerprint;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /** JPA no-arg constructor. */
    protected JobRecord() {
    }

    /**
     * Creates a new job record at intake.
     *
     * @param jobId                 UUID assigned at intake
     * @param submitterKeyId        the registered submitter key ID
     * @param phaseId               audit-only phase identifier from phaseDef
     * @param rawPhaseDefJson       the original phaseDef JSON
     * @param canonicalPhaseDefJson JSON of the canonical phase definition
     * @param fingerprint           32-byte structural fingerprint
     * @param status                initial status (typically {@code "queued"})
     * @param submittedAt           intake timestamp
     */
    public JobRecord(UUID jobId, UUID submitterKeyId, int phaseId,
                     String rawPhaseDefJson, String canonicalPhaseDefJson,
                     byte[] fingerprint, String status, Instant submittedAt) {
        this.jobId = jobId;
        this.submitterKeyId = submitterKeyId;
        this.phaseId = phaseId;
        this.rawPhaseDefJson = rawPhaseDefJson;
        this.canonicalPhaseDefJson = canonicalPhaseDefJson;
        this.fingerprint = fingerprint;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public UUID getJobId() { return jobId; }
    public UUID getSubmitterKeyId() { return submitterKeyId; }
    public int getPhaseId() { return phaseId; }
    public String getRawPhaseDefJson() { return rawPhaseDefJson; }
    public String getCanonicalPhaseDefJson() { return canonicalPhaseDefJson; }
    public byte[] getFingerprint() { return fingerprint; }
    public String getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }

    /** Updates the job status (called by E01S07 when the job is picked up for decomposition). */
    public void setStatus(String status) { this.status = status; }
}
