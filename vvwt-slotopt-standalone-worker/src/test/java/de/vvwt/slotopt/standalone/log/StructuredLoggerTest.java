// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.log;

import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cross-package contract test for the {@link StructuredLogger} interface.
 *
 * <p>Per DEC-36: this test is in the {@code log} package — different from {@code log.internal}.
 * References {@link StructuredLogger} only via interface.
 *
 * <p>Story: E41S05 AC-STRUCTURED-LOGGER.
 */
@ExtendWith(MockitoExtension.class)
class StructuredLoggerTest {

    @Mock private StructuredLogger logger;

    /** TC-8: StructuredLogger.info can be invoked with event + fields map. */
    @Test
    void info_interfaceInvocable() {
        logger.info("packet_pulled", Map.of("packetId", "abc", "jobId", "xyz"));
        verify(logger).info("packet_pulled", Map.of("packetId", "abc", "jobId", "xyz"));
    }

    /** TC-9: StructuredLogger.error can be invoked with event + message. */
    @Test
    void error_interfaceInvocable() {
        logger.error("submit_rejected_deprecated", "algorithm Ed25519 deprecated");
        verify(logger).error("submit_rejected_deprecated", "algorithm Ed25519 deprecated");
    }

    /** TC-10: StructuredLogger.warn can be invoked. */
    @Test
    void warn_interfaceInvocable() {
        logger.warn("deprecation_warning", "algorithm will expire 2027-01-01");
        verify(logger).warn("deprecation_warning", "algorithm will expire 2027-01-01");
    }
}
