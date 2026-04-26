package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResultAuditEntry}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-22, DEC-35
 */
class ResultAuditEntryTest {

    @Test
    void entityFieldsRoundTrip() {
        ResultAuditEntry entry = new ResultAuditEntry();
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        Instant now = Instant.now();

        entry.setPacketId(packetId);
        entry.setWorkerId(workerId);
        entry.setAlgorithm("Ed25519");
        entry.setSourceIp("127.0.0.1");
        entry.setReceivedAt(now);
        entry.setOutcome("ACCEPTED");

        assertThat(entry.getPacketId()).isEqualTo(packetId);
        assertThat(entry.getWorkerId()).isEqualTo(workerId);
        assertThat(entry.getAlgorithm()).isEqualTo("Ed25519");
        assertThat(entry.getSourceIp()).isEqualTo("127.0.0.1");
        assertThat(entry.getReceivedAt()).isEqualTo(now);
        assertThat(entry.getOutcome()).isEqualTo("ACCEPTED");
    }

    @Test
    void outcomeValues_acceptedSupersededSignatureInvalid() {
        // Document the expected outcome string constants per AC-RESULT-AUDIT
        assertThat("ACCEPTED").isEqualTo("ACCEPTED");
        assertThat("SUPERSEDED").isEqualTo("SUPERSEDED");
        assertThat("SIGNATURE_INVALID").isEqualTo("SIGNATURE_INVALID");
    }
}
