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
 * Append-only audit log entry for every {@code POST /submit-result} call (E01S08 AC8).
 *
 * <p>Every submission is logged regardless of outcome — success, rejection, or error —
 * per DEC-6 mechanism 10. The row is committed in a separate transaction
 * ({@code REQUIRES_NEW}) so it persists even if the outer transaction is rolled back.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code loggedAt} — server-side wall clock at log time</li>
 *   <li>{@code sourceIp} — remote address from the HTTP request</li>
 *   <li>{@code workerKeyId} — the worker key ID from the request; may be null if unparseable</li>
 *   <li>{@code packetId} — the packet ID from the request; may be null</li>
 *   <li>{@code jobId} — the job ID from the request; may be null</li>
 *   <li>{@code signatureOutcome} — {@code VERIFIED}, {@code FAILED}, or {@code N/A}</li>
 *   <li>{@code decisionOutcome} — one of the {@code DECISION_*} constants</li>
 *   <li>{@code httpStatus} — the HTTP response status code returned</li>
 * </ul>
 *
 * <p>See Story E01S08 AC8 and DEC-6 mechanism 10.
 */
@Entity
@Table(name = "result_audit_log")
public class ResultAuditEntry {

    // -------------------------------------------------------------------------
    // Outcome constants
    // -------------------------------------------------------------------------

    public static final String SIG_VERIFIED = "VERIFIED";
    public static final String SIG_FAILED   = "FAILED";
    public static final String SIG_NA       = "N/A";

    public static final String DECISION_ACCEPTED_FIRST      = "ACCEPTED_FIRST";
    public static final String DECISION_ACCEPTED_LATE       = "ACCEPTED_LATE";
    public static final String DECISION_REJECTED_NOT_ASSIGNED = "REJECTED_NOT_ASSIGNED";
    public static final String DECISION_REJECTED_DEADLINE   = "REJECTED_DEADLINE";
    public static final String DECISION_REJECTED_SIGNATURE  = "REJECTED_SIGNATURE";
    public static final String DECISION_REJECTED_OTHER      = "REJECTED_OTHER";

    // -------------------------------------------------------------------------
    // Columns
    // -------------------------------------------------------------------------

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id", nullable = false, updatable = false)
    private Long auditId;

    @Column(name = "logged_at", nullable = false, updatable = false)
    private Instant loggedAt;

    @Column(name = "source_ip", nullable = false, updatable = false, length = 64)
    private String sourceIp;

    @Column(name = "worker_key_id", updatable = false)
    private UUID workerKeyId;

    @Column(name = "packet_id", updatable = false)
    private UUID packetId;

    @Column(name = "job_id", updatable = false)
    private UUID jobId;

    @Column(name = "signature_outcome", updatable = false, length = 16)
    private String signatureOutcome;

    @Column(name = "decision_outcome", updatable = false, length = 32)
    private String decisionOutcome;

    @Column(name = "http_status", nullable = false, updatable = false)
    private int httpStatus;

    /** JPA no-arg constructor. */
    protected ResultAuditEntry() {
    }

    /**
     * Creates a new audit entry for a submit-result call.
     *
     * @param loggedAt         server-side timestamp
     * @param sourceIp         remote IP
     * @param workerKeyId      key ID from the request (may be null)
     * @param packetId         packet ID from the request (may be null)
     * @param jobId            job ID from the request (may be null)
     * @param signatureOutcome one of the {@code SIG_*} constants
     * @param decisionOutcome  one of the {@code DECISION_*} constants
     * @param httpStatus       HTTP response status code
     */
    public ResultAuditEntry(Instant loggedAt, String sourceIp,
                            UUID workerKeyId, UUID packetId, UUID jobId,
                            String signatureOutcome, String decisionOutcome,
                            int httpStatus) {
        this.loggedAt         = loggedAt;
        this.sourceIp         = sourceIp;
        this.workerKeyId      = workerKeyId;
        this.packetId         = packetId;
        this.jobId            = jobId;
        this.signatureOutcome = signatureOutcome;
        this.decisionOutcome  = decisionOutcome;
        this.httpStatus       = httpStatus;
    }

    public Long getAuditId()          { return auditId; }
    public Instant getLoggedAt()       { return loggedAt; }
    public String getSourceIp()        { return sourceIp; }
    public UUID getWorkerKeyId()       { return workerKeyId; }
    public UUID getPacketId()          { return packetId; }
    public UUID getJobId()             { return jobId; }
    public String getSignatureOutcome(){ return signatureOutcome; }
    public String getDecisionOutcome() { return decisionOutcome; }
    public int getHttpStatus()         { return httpStatus; }
}
