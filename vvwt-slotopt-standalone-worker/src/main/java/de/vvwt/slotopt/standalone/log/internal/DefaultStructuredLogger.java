// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.log.internal;

import de.vvwt.slotopt.standalone.log.StructuredLogger;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link StructuredLogger} using slf4j.
 *
 * <p>Emits structured events formatted as {@code event=<name> key1=v1 key2=v2 ...} per E37S02 spec
 * § (c) "Observability". Each log entry starts with {@code event=<name>} followed by the provided
 * fields in their natural Map iteration order.
 *
 * <p>DEC-35-by-analogy: implementation in {@code log.internal}; public interface {@link
 * StructuredLogger} in {@code log}.
 *
 * <p>Story: E41S05 AC-STRUCTURED-LOGGER.
 */
public class DefaultStructuredLogger implements StructuredLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStructuredLogger.class);

    /**
     * Formats an event and its fields into a structured string.
     *
     * <p>Package-visible for white-box testing in {@code log.internal} test package.
     *
     * @param event the event name
     * @param fields additional key-value fields
     * @return formatted string of the form {@code event=<name> key1=v1 key2=v2}
     */
    String formatEvent(String event, Map<String, String> fields) {
        if (fields.isEmpty()) {
            return "event=" + event;
        }
        StringBuilder sb = new StringBuilder("event=").append(event);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /** {@inheritDoc} */
    @Override
    public void info(String event, Map<String, String> fields) {
        LOG.info(formatEvent(event, fields));
    }

    /** {@inheritDoc} */
    @Override
    public void error(String event, String message) {
        LOG.error("event={} message={}", event, message);
    }

    /** {@inheritDoc} */
    @Override
    public void warn(String event, String message) {
        LOG.warn("event={} message={}", event, message);
    }
}
