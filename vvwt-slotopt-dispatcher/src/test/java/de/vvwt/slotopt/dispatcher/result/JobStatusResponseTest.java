package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobStatusResponse}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09; AC-JOB-STATUS-CONTROLLER; DEC-22
 */
class JobStatusResponseTest {

    @Test
    void recordFieldsAreAccessible() {
        UUID jobId = UUID.randomUUID();
        JobStatusResponse resp = new JobStatusResponse(jobId, "RECEIVED", 10, 4);

        assertThat(resp.jobId()).isEqualTo(jobId);
        assertThat(resp.status()).isEqualTo("RECEIVED");
        assertThat(resp.totalPackets()).isEqualTo(10);
        assertThat(resp.completedPackets()).isEqualTo(4);
    }
}
