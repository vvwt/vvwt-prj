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
     * Deadline by which the assigned worker must submit the result (E01S08 AC7).
     * Set when the packet is assigned (pull-packet); null when not assigned.
     */
    @Column(name = "deadline")
    private Instant deadline;

    /**
     * JSON array of reissue audit entries (TEXT column). Null until the first reissue.
     * Each entry: {@code {"timestamp":"<ISO-8601>","workerKeyId":"<UUID>","attemptNumber":<N>}}.
     */
    @Column(name = "reissue_history", columnDefinition = "TEXT")
    private String reissueHistory;

    // -------------------------------------------------------------------------
    // First-valid-wins result fields (E01S08 AC3)
    // Set when the packet transitions to "done" with its first accepted result.
    // -------------------------------------------------------------------------

    /** Best permutation rank from the first accepted result; null until packet is done. */
    @Column(name = "first_best_rank")
    private Long firstBestRank;

    /** Best variety score from the first accepted result; null until packet is done. */
    @Column(name = "first_best_score")
    private Double firstBestScore;

    /** Key ID of the worker whose result was accepted first; null until packet is done. */
    @Column(name = "first_worker_key_id")
    private UUID firstWorkerKeyId;

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
    public Instant getDeadline() { return deadline; }
    public String getReissueHistory() { return reissueHistory; }
    public Long getFirstBestRank() { return firstBestRank; }
    public Double getFirstBestScore() { return firstBestScore; }
    public UUID getFirstWorkerKeyId() { return firstWorkerKeyId; }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * Claims this packet for a worker (AC5).
     *
     * <p>Sets {@code status='assigned'}, records the worker, timestamp, and deadline,
     * and increments the attempt counter.
     *
     * @param workerKeyId      the worker that claimed this packet
     * @param at               assignment timestamp
     * @param deadlineInstant  submission deadline for this assignment (E01S08 AC7)
     */
    public void assign(UUID workerKeyId, Instant at, Instant deadlineInstant) {
        this.status = "assigned";
        this.assignedTo = workerKeyId;
        this.assignedAt = at;
        this.deadline = deadlineInstant;
        this.attempts++;
    }

    /**
     * Claims this packet for a worker without setting an explicit deadline (legacy overload).
     *
     * <p>Provided for backward compatibility with existing tests that do not set a deadline.
     * Production code MUST use {@link #assign(UUID, Instant, Instant)} to ensure the deadline
     * is always populated for E01S08 AC7.
     *
     * @param workerKeyId the worker that claimed this packet
     * @param at          assignment timestamp
     */
    public void assign(UUID workerKeyId, Instant at) {
        assign(workerKeyId, at, null);
    }

    /**
     * Accepts the first valid result for this packet (E01S08 AC3).
     *
     * <p>Transitions {@code status} to {@code "done"}, clears the worker assignment fields,
     * and stores the first-result data for finalization and late-result comparison.
     *
     * @param workerKeyId the worker whose result is accepted
     * @param bestRank    the best permutation rank found
     * @param bestScore   the variety score for {@code bestRank}
     */
    public void acceptFirstResult(UUID workerKeyId, long bestRank, double bestScore) {
        this.status = "done";
        this.assignedTo = null;
        this.assignedAt = null;
        this.deadline = null;
        this.firstWorkerKeyId = workerKeyId;
        this.firstBestRank = bestRank;
        this.firstBestScore = bestScore;
    }

    /**
     * Reissues this packet to the pending pool after a timeout (AC8).
     *
     * <p>Clears {@code assignedTo}, {@code assignedAt}, and {@code deadline} and sets
     * {@code status='pending'}. The {@code attempts} counter is NOT reset.
     * Call {@link #appendReissueHistory(String)} BEFORE calling this method to
     * preserve the outgoing worker information in the audit log.
     */
    public void reissueToPending() {
        this.status = "pending";
        this.assignedTo = null;
        this.assignedAt = null;
        this.deadline = null;
    }

    /**
     * Marks this packet as permanently failed (AC8 max-reissue threshold).
     */
    public void markFailed() {
        this.status = "failed";
        this.assignedTo = null;
        this.assignedAt = null;
        this.deadline = null;
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
