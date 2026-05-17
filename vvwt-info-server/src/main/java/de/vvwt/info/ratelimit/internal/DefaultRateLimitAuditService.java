// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit.internal;

import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.AuditLogRecord;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;
import de.vvwt.info.ratelimit.RateLimitAuditService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Emits audit-log rows for rate-limit rejections (E38S07 AC4, AC7).
 *
 * <p>Builds a fully-populated {@link AuditLogRecord} with the D-X5 split encoding:
 *
 * <ul>
 *   <li>{@code signature_outcome = NA} — rate-limit fires before signature verification
 *   <li>{@code rejection_reason = RATE_LIMITED} — the application-level rejection cause
 * </ul>
 *
 * <p>Consumes the established {@link AuditLogDao} from E38S03 (AC7 — does not re-implement DAO IT;
 * three DEC-26/DEC-46 rules are satisfied by E38S03's DAO IT).
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC4,
 *     AC7</a>
 */
public class DefaultRateLimitAuditService implements RateLimitAuditService {

    private final AuditLogDao auditLogDao;

    public DefaultRateLimitAuditService(AuditLogDao auditLogDao) {
        this.auditLogDao = auditLogDao;
    }

    /**
     * Emits an audit-log row for a rate-limited request.
     *
     * @param sourceIp the resolved source IP (from {@link
     *     de.vvwt.info.ratelimit.SourceIpExtractor})
     * @param tenantId the tenant ID extracted from the request path; may be {@code null}
     * @param tournamentId the tournament ID extracted from the request path; may be {@code null}
     * @param requestPath the HTTP request path (no query string)
     */
    @Override
    public void emitRateLimitedAuditRow(
            String sourceIp, String tenantId, String tournamentId, String requestPath) {
        AuditLogRecord record =
                new AuditLogRecord(
                        null, // id — auto-generated
                        UUID.randomUUID().toString(), // request_id — server-minted UUIDv4
                        sourceIp,
                        LocalDateTime.now(ZoneOffset.UTC), // timestamp_utc
                        SignatureOutcome.NA, // rate-limit before signature verification
                        RejectionReason.RATE_LIMITED,
                        429,
                        tenantId,
                        tournamentId,
                        requestPath);
        auditLogDao.append(record);
    }
}
