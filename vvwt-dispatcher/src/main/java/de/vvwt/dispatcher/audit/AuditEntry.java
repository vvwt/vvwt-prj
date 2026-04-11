package de.vvwt.dispatcher.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit log entry for every {@code register-key} and {@code submit-job} call.
 *
 * <p>Every call is logged regardless of outcome — success or failure — per AC12 of E01S06.
 * Fields:
 * <ul>
 *   <li>{@code loggedAt} — wall clock at log time (server-side)</li>
 *   <li>{@code sourceIp} — remote address from {@code HttpServletRequest}</li>
 *   <li>{@code endpoint} — {@code /register-key} or {@code /submit-job}</li>
 *   <li>{@code keyId} — the key ID involved in the call; null if resolution failed</li>
 *   <li>{@code signatureOutcome} — {@code VERIFIED}, {@code FAILED}, or {@code N/A}</li>
 *   <li>{@code httpStatus} — the HTTP response status actually returned</li>
 * </ul>
 *
 * <p>This entity is NEVER updated after creation. Rows must not be deleted except by
 * a privileged archival process outside of the dispatcher.
 *
 * <p>See Story E01S06 AC12 and DEC-6.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

    /** Signature outcome when no signature verification was attempted (register-key). */
    public static final String OUTCOME_NA = "N/A";

    /** Signature outcome when verification succeeded. */
    public static final String OUTCOME_VERIFIED = "VERIFIED";

    /** Signature outcome when verification failed. */
    public static final String OUTCOME_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id", nullable = false, updatable = false)
    private Long auditId;

    @Column(name = "logged_at", nullable = false, updatable = false)
    private Instant loggedAt;

    @Column(name = "source_ip", nullable = false, updatable = false, length = 64)
    private String sourceIp;

    @Column(name = "endpoint", nullable = false, updatable = false, length = 32)
    private String endpoint;

    /** Null when the key ID was not resolved at call time (e.g., unknown submitterKeyId). */
    @Column(name = "key_id", updatable = false)
    private UUID keyId;

    /** One of {@link #OUTCOME_NA}, {@link #OUTCOME_VERIFIED}, {@link #OUTCOME_FAILED}. */
    @Column(name = "signature_outcome", updatable = false, length = 16)
    private String signatureOutcome;

    @Column(name = "http_status", nullable = false, updatable = false)
    private int httpStatus;

    /** JPA no-arg constructor. */
    protected AuditEntry() {
    }

    /**
     * Creates a new audit entry.
     *
     * @param loggedAt          timestamp
     * @param sourceIp          remote IP from the HTTP request
     * @param endpoint          {@code /register-key} or {@code /submit-job}
     * @param keyId             resolved key ID, or null
     * @param signatureOutcome  one of the {@code OUTCOME_*} constants
     * @param httpStatus        HTTP response status code
     */
    public AuditEntry(Instant loggedAt, String sourceIp, String endpoint,
                      UUID keyId, String signatureOutcome, int httpStatus) {
        this.loggedAt = loggedAt;
        this.sourceIp = sourceIp;
        this.endpoint = endpoint;
        this.keyId = keyId;
        this.signatureOutcome = signatureOutcome;
        this.httpStatus = httpStatus;
    }

    public Long getAuditId() { return auditId; }
    public Instant getLoggedAt() { return loggedAt; }
    public String getSourceIp() { return sourceIp; }
    public String getEndpoint() { return endpoint; }
    public UUID getKeyId() { return keyId; }
    public String getSignatureOutcome() { return signatureOutcome; }
    public int getHttpStatus() { return httpStatus; }
}
