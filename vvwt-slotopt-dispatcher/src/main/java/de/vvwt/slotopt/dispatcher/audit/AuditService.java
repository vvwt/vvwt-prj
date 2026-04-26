package de.vvwt.slotopt.dispatcher.audit;

import java.util.UUID;

/**
 * Service interface for recording dispatcher audit events.
 *
 * <p>Per DEC-35: this interface lives in the public {@code audit} package. The implementation
 * ({@link de.vvwt.slotopt.dispatcher.audit.internal.DefaultAuditService}) lives in {@code
 * audit.internal}.
 *
 * <p>All consumers (services, tests) type their dependency as {@code AuditService}, never as the
 * implementation class (DEC-36).
 *
 * <p>Failure semantics (AC-AUDIT-FAILURE-MODE — enforced by the implementation):
 *
 * <ul>
 *   <li>Null/empty/blank {@code eventType} → throws {@link IllegalArgumentException} (programmer
 *       error, fast-fail at call site).
 *   <li>Database persist failure → logs at WARN level with structured context and does NOT rethrow.
 *       Audit failures MUST NOT block the originating registration operation.
 *   <li>Oversize {@code detailJson} (exceeds configurable bound, default 64 KB) → truncated with a
 *       {@code [TRUNCATED]} marker to preserve partial forensic evidence.
 * </ul>
 *
 * <p>Story: E37S06; AC-AUDIT-SERVICE; DEC-6, DEC-35
 */
public interface AuditService {

    /**
     * Records a dispatcher audit event.
     *
     * @param eventType event type string (e.g., {@code KEY_REGISTERED}, {@code KEY_ROLE_CONFLICT},
     *     {@code KEY_RE_REGISTRATION_IDEMPOTENT}); must not be null, empty, or blank
     * @param workerId worker UUID whose key triggered this event; may be {@code null} if the event
     *     has no worker context
     * @param sourceIp source IP address of the request (IPv4 or IPv6 notation); must not be {@code
     *     null}
     * @param detailJson free-form structured event payload as a JSON string; may be {@code null} or
     *     empty; oversize values are silently truncated
     * @throws IllegalArgumentException if {@code eventType} is null, empty, or blank
     */
    void recordEvent(String eventType, UUID workerId, String sourceIp, String detailJson);
}
