package de.vvwt.dispatcher.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of a late result submission (E01S08 AC4).
 *
 * <p>A late result is a valid signed submission for a packet that is already
 * {@code "done"} — i.e., a first valid result has already been accepted.
 * Per DEC-6 mechanism 11, late results are logged but never override the first.
 *
 * <p>If {@code matchesFirst} is {@code false}, a WARN log is emitted (AC5)
 * indicating a discrepancy between the first and late results — a tamper-detection
 * signal per Brief O-9 / D-11.
 *
 * <p>This entity is NEVER updated. Rows are append-only.
 *
 * <p>See Story E01S08 AC4, AC5 and DEC-6 mechanism 11.
 */
@Entity
@Table(name = "late_results")
public class LateResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "late_result_id", nullable = false, updatable = false)
    private Long lateResultId;

    @Column(name = "packet_id", nullable = false, updatable = false)
    private UUID packetId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "worker_key_id", nullable = false, updatable = false)
    private UUID workerKeyId;

    @Column(name = "best_rank", nullable = false, updatable = false)
    private long bestRank;

    @Column(name = "best_score", nullable = false, updatable = false)
    private double bestScore;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /**
     * {@code true} if {@code (bestRank, bestScore)} matches the first accepted result.
     * {@code false} signals a potential tamper/defect event (AC5).
     */
    @Column(name = "matches_first", nullable = false, updatable = false)
    private boolean matchesFirst;

    /** JPA no-arg constructor. */
    protected LateResult() {
    }

    /**
     * Creates a late result log entry.
     *
     * @param packetId     the packet for which this late result was submitted
     * @param jobId        the owning job
     * @param workerKeyId  the worker that submitted this late result
     * @param bestRank     the best permutation rank from this submission
     * @param bestScore    the variety score for {@code bestRank}
     * @param receivedAt   server-side timestamp when the submission was received
     * @param matchesFirst {@code true} if this matches the first accepted result
     */
    public LateResult(UUID packetId, UUID jobId, UUID workerKeyId,
                      long bestRank, double bestScore, Instant receivedAt, boolean matchesFirst) {
        this.packetId     = packetId;
        this.jobId        = jobId;
        this.workerKeyId  = workerKeyId;
        this.bestRank     = bestRank;
        this.bestScore    = bestScore;
        this.receivedAt   = receivedAt;
        this.matchesFirst = matchesFirst;
    }

    public Long getLateResultId() { return lateResultId; }
    public UUID getPacketId()     { return packetId; }
    public UUID getJobId()        { return jobId; }
    public UUID getWorkerKeyId()  { return workerKeyId; }
    public long getBestRank()     { return bestRank; }
    public double getBestScore()  { return bestScore; }
    public Instant getReceivedAt(){ return receivedAt; }
    public boolean isMatchesFirst(){ return matchesFirst; }
}
