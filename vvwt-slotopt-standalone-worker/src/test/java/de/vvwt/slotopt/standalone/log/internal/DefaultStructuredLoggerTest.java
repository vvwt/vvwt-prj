package de.vvwt.slotopt.standalone.log.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Same-package white-box tests for {@link DefaultStructuredLogger}.
 *
 * <p>Per DEC-36 same-package carve-out: test is in {@code log.internal} and may reference {@link
 * DefaultStructuredLogger} directly (white-box).
 *
 * <p>Story: E41S05 AC-STRUCTURED-LOGGER.
 */
class DefaultStructuredLoggerTest {

    /** TC-11: info event formats as "event=<name> key1=v1 key2=v2". */
    @Test
    void formatEvent_info_producesStructuredString() {
        DefaultStructuredLogger logger = new DefaultStructuredLogger();
        String formatted = logger.formatEvent("packet_pulled", Map.of("packetId", "abc"));
        assertThat(formatted).startsWith("event=packet_pulled");
        assertThat(formatted).contains("packetId=abc");
    }

    /** TC-12: info with empty fields map produces "event=<name>". */
    @Test
    void formatEvent_emptyFields_producesEventOnly() {
        DefaultStructuredLogger logger = new DefaultStructuredLogger();
        String formatted = logger.formatEvent("worker_stopped", Map.of());
        assertThat(formatted).isEqualTo("event=worker_stopped");
    }

    /** TC-13: info invocation does not throw for all standard runtime events. */
    @Test
    void info_standardRuntimeEvents_noThrow() {
        DefaultStructuredLogger logger = new DefaultStructuredLogger();
        // All 5 runtime events from AC-OBSERVABILITY-EVENTS-RUNTIME
        logger.info("packet_pulled", Map.of("packetId", "p1", "jobId", "j1"));
        logger.info(
                "packet_processed", Map.of("packetId", "p1", "jobId", "j1", "durationMs", "42"));
        logger.info(
                "result_submitted", Map.of("packetId", "p1", "jobId", "j1", "accepted", "true"));
        logger.info(
                "result_superseded",
                Map.of("packetId", "p1", "jobId", "j1", "reason", "superseded"));
        logger.info("worker_stopped", Map.of("exitCode", "0"));
    }

    /** TC-14: error invocation does not throw. */
    @Test
    void error_doesNotThrow() {
        DefaultStructuredLogger logger = new DefaultStructuredLogger();
        logger.error("submit_rejected_deprecated", "algorithm Ed25519 deprecated");
    }

    /** TC-15: warn invocation does not throw. */
    @Test
    void warn_doesNotThrow() {
        DefaultStructuredLogger logger = new DefaultStructuredLogger();
        logger.warn("deprecation_warning", "algorithm will expire 2027-01-01");
    }
}
