package de.vvwt.slotopt.dispatcher.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code audit_entry} table.
 *
 * <p>Captures identity/registration audit events per DEC-6 (source IP, timestamp, signature
 * outcome). This is a mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC
 * POJOs at the public surface.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code eventType} — one of {@code KEY_REGISTERED}, {@code KEY_ROLE_CONFLICT}, {@code
 *       KEY_RE_REGISTRATION_IDEMPOTENT}.
 *   <li>{@code workerId} — nullable: some events have no worker context.
 *   <li>{@code sourceIp} — source IP address of the request; max 45 chars (IPv6).
 *   <li>{@code detailJson} — free-form structured event payload; max 65536 chars; truncated with
 *       marker if oversize (see {@code DefaultAuditService}).
 * </ul>
 *
 * <p>Story: E37S06; AC-AUDIT-ENTRY-ENTITY; Spec: E37S02 spec (a); DEC-6, DEC-35
 */
@Table("audit_entry")
public class AuditEntry {

    @Id private Long id;

    /** Timestamp when the event occurred. NOT NULL. */
    private Instant occurredAt;

    /**
     * Event type string (e.g., {@code KEY_REGISTERED}). NOT NULL. Validated by {@code
     * DefaultAuditService}: null/empty/blank → {@link IllegalArgumentException}.
     */
    private String eventType;

    /**
     * Worker UUID whose registration generated this event. Nullable: some events have no worker
     * context.
     */
    private UUID workerId;

    /** Source IP address of the request. NOT NULL. Max 45 characters (IPv6 CIDR notation). */
    private String sourceIp;

    /**
     * Free-form structured event payload as JSON string. Nullable. Oversize values are truncated
     * with a {@code [TRUNCATED]} marker by {@code DefaultAuditService}.
     */
    private String detailJson;

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public void setWorkerId(UUID workerId) {
        this.workerId = workerId;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }
}
