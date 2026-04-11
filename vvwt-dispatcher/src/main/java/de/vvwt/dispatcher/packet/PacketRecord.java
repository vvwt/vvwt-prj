package de.vvwt.dispatcher.packet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a rank-interval packet created for a decomposed job.
 *
 * <p>Created by {@link PacketDecomposerService} when a job transitions from
 * {@code "queued"} to {@code "ready"} (E01S07 AC1).
 *
 * <p>Status lifecycle:
 * <pre>
 *   pending  ──(pull-packet)──▶  assigned  ──(submit-result / E01S08)──▶  done
 *              ◀──(timeout sweeper, AC8)──
 *              ──(max reissue exceeded)──▶  failed
 * </pre>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code packetId} — UUID assigned at creation</li>
 *   <li>{@code jobId} — references {@code jobs.job_id}</li>
 *   <li>{@code rankFrom} — inclusive start of the rank interval (0-based Lehmer rank)</li>
 *   <li>{@code rankTo} — exclusive end of the rank interval</li>
 *   <li>{@code status} — {@code "pending"}, {@code "assigned"}, {@code "done"}, or {@code "failed"}</li>
 *   <li>{@code attempts} — total number of times this packet has been assigned (incremented on each claim)</li>
 *   <li>{@code assignedTo} — workerKeyId currently holding this packet; null when not assigned</li>
 *   <li>{@code assignedAt} — timestamp when last assigned; null when not assigned</li>
 *   <li>{@code reissueHistory} — TEXT column storing a JSON array of reissue events (AC8);
 *       each entry: {@code {"timestamp":"...","workerKeyId":"...","attemptNumber":N}}</li>
 * </ul>
 *
 * <p>See Story E01S07 AC1–AC8.
 */
@Entity
@Table(name = "packets")
public class PacketRecord {

    @Id
    @Column(name = "packet_id", nullable = false, updatable = false)
    private UUID packetId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "rank_from", nullable = false, updatable = false)
    private long rankFrom;

    @Column(name = "rank_to", nullable = false, updatable = false)
    private long rankTo;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Null when the packet is {@code pending}, {@code done}, or {@code failed}. */
    @Column(name = "assigned_to")
    private UUID assignedTo;

    /** Null when the packet is {@code pending}, {@code done}, or {@code failed}. */
    @Column(name = "assigned_at")
    private Instant assignedAt;

    /**
     * JSON array of reissue audit entries (TEXT column). Null until the first reissue.
     * Each entry: {@code {"timestamp":"<ISO-8601>","workerKeyId":"<UUID>","attemptNumber":<N>}}.
     */
    @Column(name = "reissue_history", columnDefinition = "TEXT")
    private String reissueHistory;

    /** JPA no-arg constructor. */
    protected PacketRecord() {
    }

    /**
     * Creates a new packet in {@code "pending"} state.
     *
     * @param packetId  UUID assigned at creation
     * @param jobId     owning job UUID
     * @param rankFrom  inclusive start rank (0-based Lehmer)
     * @param rankTo    exclusive end rank
     */
    public PacketRecord(UUID packetId, UUID jobId, long rankFrom, long rankTo) {
        this.packetId = packetId;
        this.jobId = jobId;
        this.rankFrom = rankFrom;
        this.rankTo = rankTo;
        this.status = "pending";
        this.attempts = 0;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getPacketId() { return packetId; }
    public UUID getJobId() { return jobId; }
    public long getRankFrom() { return rankFrom; }
    public long getRankTo() { return rankTo; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public UUID getAssignedTo() { return assignedTo; }
    public Instant getAssignedAt() { return assignedAt; }
    public String getReissueHistory() { return reissueHistory; }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Claims this packet for a worker (AC5).
     *
     * <p>Sets {@code status='assigned'}, records the worker and timestamp,
     * and increments the attempt counter.
     *
     * @param workerKeyId the worker that claimed this packet
     * @param at          assignment timestamp
     */
    public void assign(UUID workerKeyId, Instant at) {
        this.status = "assigned";
        this.assignedTo = workerKeyId;
        this.assignedAt = at;
        this.attempts++;
    }

    /**
     * Reissues this packet to the pending pool after a timeout (AC8).
     *
     * <p>Clears {@code assignedTo} and {@code assignedAt} and sets {@code status='pending'}.
     * The {@code attempts} counter is NOT reset — it tracks the total claim count.
     * Call {@link #appendReissueHistory(String)} BEFORE calling this method to
     * preserve the outgoing worker information in the audit log.
     */
    public void reissueToPending() {
        this.status = "pending";
        this.assignedTo = null;
        this.assignedAt = null;
    }

    /**
     * Marks this packet as permanently failed (AC8 max-reissue threshold).
     */
    public void markFailed() {
        this.status = "failed";
        this.assignedTo = null;
        this.assignedAt = null;
    }

    /**
     * Appends one JSON entry to the {@code reissue_history} field (AC8).
     *
     * <p>The history is stored as a JSON array in a TEXT column.
     * A null or empty history is initialized to a one-element array.
     *
     * @param jsonEntry a JSON object string (must NOT include enclosing brackets)
     */
    public void appendReissueHistory(String jsonEntry) {
        if (this.reissueHistory == null || this.reissueHistory.isBlank()) {
            this.reissueHistory = "[" + jsonEntry + "]";
        } else {
            // Insert before the closing bracket
            this.reissueHistory = this.reissueHistory.substring(
                    0, this.reissueHistory.lastIndexOf(']'))
                    + "," + jsonEntry + "]";
        }
    }
}
