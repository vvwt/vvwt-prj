// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.log;

import java.util.Map;

/**
 * Emits structured observability events for the standalone-worker process.
 *
 * <p>Per E37S02 spec § (c) "Observability": events are emitted as structured slf4j log entries with
 * key=value fields. The canonical event format is:
 *
 * <pre>
 * event=&lt;name&gt; key1=v1 key2=v2 ...
 * </pre>
 *
 * <p>DEC-35-by-analogy: public interface in the {@code log} package root; canonical implementation
 * in {@link de.vvwt.slotopt.standalone.log.internal.DefaultStructuredLogger}.
 *
 * <p>Story: E41S05 AC-STRUCTURED-LOGGER.
 */
public interface StructuredLogger {

    /**
     * Emits an INFO-level structured event.
     *
     * @param event the event name (e.g., {@code "packet_pulled"}); must not be {@code null}
     * @param fields additional key-value fields for the event; may be empty but not {@code null}
     */
    void info(String event, Map<String, String> fields);

    /**
     * Emits an ERROR-level structured event.
     *
     * @param event the event name; must not be {@code null}
     * @param message the error message; must not be {@code null}
     */
    void error(String event, String message);

    /**
     * Emits a WARN-level structured event.
     *
     * @param event the event name; must not be {@code null}
     * @param message the warning message; must not be {@code null}
     */
    void warn(String event, String message);
}
