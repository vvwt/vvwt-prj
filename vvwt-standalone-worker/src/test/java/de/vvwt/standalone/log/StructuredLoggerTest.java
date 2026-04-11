package de.vvwt.standalone.log;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StructuredLogger} — JSON vs plain output and JSON escaping.
 * Implements Story E01S05 AC8.
 */
class StructuredLoggerTest {

    @Test
    void jsonEscape_plainString_unchanged() {
        assertThat(StructuredLogger.jsonEscape("hello world")).isEqualTo("hello world");
    }

    @Test
    void jsonEscape_doubleQuotes_escaped() {
        assertThat(StructuredLogger.jsonEscape("say \"hello\"")).isEqualTo("say \\\"hello\\\"");
    }

    @Test
    void jsonEscape_backslash_escaped() {
        assertThat(StructuredLogger.jsonEscape("path\\to\\file")).isEqualTo("path\\\\to\\\\file");
    }

    @Test
    void jsonEscape_newline_escaped() {
        assertThat(StructuredLogger.jsonEscape("line1\nline2")).isEqualTo("line1\\nline2");
    }

    @Test
    void jsonEscape_controlChar_unicodeEscaped() {
        // ASCII 0x01 = SOH
        assertThat(StructuredLogger.jsonEscape("\u0001")).isEqualTo("\\u0001");
    }

    @Test
    void jsonEscape_null_returnsNull() {
        assertThat(StructuredLogger.jsonEscape(null)).isEqualTo("null");
    }

    @Test
    void structuredLogger_constructsWithoutError() {
        // NOPLogger discards all output — just verify no NPE or construction error
        StructuredLogger logger = new StructuredLogger(NOPLogger.NOP_LOGGER, true);
        logger.info("test-event", "test message", "key1", "val1");
        logger.warn("test-warn", "warning message");
        logger.error("test-error", "error message", "code", 42);
    }
}
