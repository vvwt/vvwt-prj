package de.vvwt.info.persistence.audit;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code audit_log} table.
 *
 * <p>The audit log is append-only by design (AC10): {@link AuditLogDao} exposes only {@code
 * append()} and read-by-id/requestId methods — no {@code update*}, {@code delete*}, or {@code
 * remove*} methods. This structural constraint is enforced by {@link AuditLogDao}'s API surface and
 * verified by {@code AuditLogDaoMethodNamesTest}.
 *
 * <p>Two columns represent the D-X5 split:
 *
 * <ul>
 *   <li>{@code signature_outcome}: cryptographic result ({@link SignatureOutcome})
 *   <li>{@code rejection_reason}: application-level rejection cause ({@link RejectionReason}),
 *       {@code null} when the request was accepted
 * </ul>
 *
 * @param id auto-generated primary key (BIGINT GENERATED ALWAYS AS IDENTITY); {@code null} before
 *     insert
 * @param requestId caller-provided idempotency / correlation identifier
 * @param sourceIp client IP address (from trusted proxy header or direct connection)
 * @param timestampUtc UTC timestamp of the request
 * @param signatureOutcome cryptographic outcome of signature verification
 * @param rejectionReason application rejection reason; {@code null} when accepted
 * @param httpStatus HTTP status code of the response
 * @param tenantId tenant identifier from the request context; may be {@code null}
 * @param tournamentId tournament identifier from the request context; may be {@code null}
 * @param requestPath HTTP request path (no query string)
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC4, AC10</a>
 */
@Table("audit_log")
public record AuditLogRecord(
        @Id @Column("id") Long id,
        @Column("request_id") String requestId,
        @Column("source_ip") String sourceIp,
        @Column("timestamp_utc") LocalDateTime timestampUtc,
        @Column("signature_outcome") SignatureOutcome signatureOutcome,
        @Column("rejection_reason") RejectionReason rejectionReason,
        @Column("http_status") int httpStatus,
        @Column("tenant_id") String tenantId,
        @Column("tournament_id") String tournamentId,
        @Column("request_path") String requestPath) {}
