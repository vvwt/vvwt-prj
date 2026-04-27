package de.vvwt.info.persistence.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit test for {@link AuditLogRecord} (DEC-22 Iron Law, AC1).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC1</a>
 */
class AuditLogRecordTest {

    @Test
    void record_fields_accessible_accepted_request() {
        var now = LocalDateTime.now();
        var record =
                new AuditLogRecord(
                        null,
                        "req-001",
                        "192.168.1.1",
                        now,
                        SignatureOutcome.VALID,
                        null,
                        200,
                        "tenant-1",
                        "t-1",
                        "/api/v1/publish");

        assertThat(record.id()).isNull(); // auto-generated, null before insert
        assertThat(record.requestId()).isEqualTo("req-001");
        assertThat(record.sourceIp()).isEqualTo("192.168.1.1");
        assertThat(record.timestampUtc()).isEqualTo(now);
        assertThat(record.signatureOutcome()).isEqualTo(SignatureOutcome.VALID);
        assertThat(record.rejectionReason()).isNull();
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.tournamentId()).isEqualTo("t-1");
        assertThat(record.requestPath()).isEqualTo("/api/v1/publish");
    }

    @Test
    void record_fields_accessible_rejected_request() {
        var now = LocalDateTime.now();
        var record =
                new AuditLogRecord(
                        null,
                        "req-002",
                        "10.0.0.1",
                        now,
                        SignatureOutcome.INVALID,
                        RejectionReason.KEY_MISMATCH,
                        403,
                        "tenant-1",
                        null,
                        "/api/v1/register");

        assertThat(record.signatureOutcome()).isEqualTo(SignatureOutcome.INVALID);
        assertThat(record.rejectionReason()).isEqualTo(RejectionReason.KEY_MISMATCH);
        assertThat(record.httpStatus()).isEqualTo(403);
        assertThat(record.tournamentId()).isNull();
    }

    @Test
    void signature_outcome_enum_values_are_stable() {
        assertThat(SignatureOutcome.values())
                .containsExactlyInAnyOrder(
                        SignatureOutcome.VALID, SignatureOutcome.INVALID, SignatureOutcome.NA);
    }

    @Test
    void rejection_reason_enum_covers_spec_values() {
        // Verify all D-X5 rejection reasons are present (AC4)
        assertThat(RejectionReason.values())
                .contains(
                        RejectionReason.KEY_MISMATCH,
                        RejectionReason.ALGORITHM_DEPRECATED,
                        RejectionReason.ALGORITHM_UNKNOWN,
                        RejectionReason.TENANT_LIMIT_EXCEEDED,
                        RejectionReason.INVITATION_INVALID,
                        RejectionReason.SEQ_MISMATCH,
                        RejectionReason.TOURNAMENT_NOT_FOUND,
                        RejectionReason.RATE_LIMITED,
                        RejectionReason.MALFORMED,
                        RejectionReason.PAYLOAD_TOO_LARGE,
                        RejectionReason.INTERNAL_ERROR);
    }
}
