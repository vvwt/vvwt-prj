// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.audit.RejectionReason;
import de.vvwt.info.persistence.audit.SignatureOutcome;
import de.vvwt.info.ratelimit.internal.DefaultRateLimitAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultRateLimitAuditService}.
 *
 * <p>DEC-22 RED-first. Verifies audit-log record fields per AC4 (D-X5 split).
 *
 * <p>Note: DEC-36 cross-package test typing — test is in different package from subject; {@link
 * AuditLogDao} is referenced via its interface (not the implementation class). Mockito mocks the
 * interface directly.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC4,
 *     AC7</a>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAuditServiceTest {

    @Mock private AuditLogDao auditLogDao;

    @InjectMocks private DefaultRateLimitAuditService rateLimitAuditService;

    @Test
    void emitRateLimitedAuditRow_populatesAllRequiredFields() {
        when(auditLogDao.append(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0)); // return the record as-is

        rateLimitAuditService.emitRateLimitedAuditRow(
                "192.0.2.1",
                "tenant-abc",
                "tournament-xyz",
                "/api/v1/publish/tenant-abc/loc/tournament-xyz");

        verify(auditLogDao)
                .append(
                        assertArg(
                                record -> {
                                    // AC4: request_id non-null UUID
                                    assertThat(record.requestId()).isNotNull();
                                    // AC4: source_ip populated
                                    assertThat(record.sourceIp()).isEqualTo("192.0.2.1");
                                    // AC4: timestamp_utc non-null
                                    assertThat(record.timestampUtc()).isNotNull();
                                    // AC4: signature_outcome = NA (rate-limit before sig
                                    // verification)
                                    assertThat(record.signatureOutcome())
                                            .isEqualTo(SignatureOutcome.NA);
                                    // AC4: rejection_reason = RATE_LIMITED
                                    assertThat(record.rejectionReason())
                                            .isEqualTo(RejectionReason.RATE_LIMITED);
                                    // AC4: http_status = 429
                                    assertThat(record.httpStatus()).isEqualTo(429);
                                    // AC4: tenant_id populated when extractable
                                    assertThat(record.tenantId()).isEqualTo("tenant-abc");
                                    // AC4: tournament_id populated when extractable
                                    assertThat(record.tournamentId()).isEqualTo("tournament-xyz");
                                    // AC4: request_path populated
                                    assertThat(record.requestPath())
                                            .isEqualTo(
                                                    "/api/v1/publish/tenant-abc/loc/tournament-xyz");
                                }));
    }

    @Test
    void emitRateLimitedAuditRow_nullTenantAndTournament_populatesNullFields() {
        when(auditLogDao.append(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        rateLimitAuditService.emitRateLimitedAuditRow("10.0.0.1", null, null, "/api/v1/poll/t/t2");

        verify(auditLogDao)
                .append(
                        assertArg(
                                record -> {
                                    assertThat(record.tenantId()).isNull();
                                    assertThat(record.tournamentId()).isNull();
                                    assertThat(record.signatureOutcome())
                                            .isEqualTo(SignatureOutcome.NA);
                                    assertThat(record.rejectionReason())
                                            .isEqualTo(RejectionReason.RATE_LIMITED);
                                }));
    }

    @Test
    void emitRateLimitedAuditRow_requestIdIsUuidV4Format() {
        when(auditLogDao.append(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        rateLimitAuditService.emitRateLimitedAuditRow("1.2.3.4", null, null, "/api/v1/poll/x/y");

        verify(auditLogDao)
                .append(
                        assertArg(
                                record -> {
                                    String requestId = record.requestId();
                                    // UUIDv4 format: 8-4-4-4-12 hex characters
                                    assertThat(requestId)
                                            .matches(
                                                    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
                                }));
    }
}
