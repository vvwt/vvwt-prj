package de.vvwt.slotopt.dispatcher.result;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code result_audit_entry} table.
 *
 * <p>Mutable POJO (NOT a record) per DEC-35. Records every submit-result call outcome for audit
 * purposes (DEC-6 § Impact: audit logs retain source IP, timestamp, and signature outcome).
 *
 * <p>Outcome values: {@code "ACCEPTED"}, {@code "SUPERSEDED"}, {@code "SIGNATURE_INVALID"}.
 *
 * <p>Schema: {@code db/migration/result/V6__create_result_audit_entry_table.sql} (DEC-26 Rule 1
 * analogous).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-6, DEC-35
 */
@Table("result_audit_entry")
public class ResultAuditEntry {

    @Id private Long id;

    /** UUID of the packet referenced by this submit-result call. NOT NULL. */
    private UUID packetId;

    /** UUID of the worker submitting the result. NOT NULL. */
    private UUID workerId;

    /**
     * Algorithm used by the worker (e.g., {@code "Ed25519"}). NOT NULL. Per DEC-43 for
     * algorithm-agility: stored alongside every audit entry.
     */
    private String algorithm;

    /** Source IP of the request (IPv4 or IPv6 text, max 45 chars). NOT NULL. */
    private String sourceIp;

    /** Timestamp when the submit-result call was received. NOT NULL. */
    private Instant receivedAt;

    /**
     * Outcome of this submit-result call. Values: {@code "ACCEPTED"}, {@code "SUPERSEDED"}, {@code
     * "SIGNATURE_INVALID"}. NOT NULL.
     */
    private String outcome;

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

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }
}
