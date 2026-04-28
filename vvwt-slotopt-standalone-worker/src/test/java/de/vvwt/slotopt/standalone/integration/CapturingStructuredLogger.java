package de.vvwt.slotopt.standalone.integration;

import de.vvwt.slotopt.standalone.log.StructuredLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Test-support implementation of {@link StructuredLogger} that captures emitted event names.
 *
 * <p>Used by {@link WorkerLauncher} to collect runtime observability events from the worker loop.
 * The event name (first argument to {@link #info}, {@link #error}, {@link #warn}) is captured for
 * assertion in integration tests.
 *
 * <p>DEC-36: implementation in the {@code integration} test package; references the public
 * interface {@link StructuredLogger} (not the {@code DefaultStructuredLogger} implementation).
 *
 * <p>Story: E41S06 AC-OBSERVABILITY-EVENT-MATRIX.
 */
class CapturingStructuredLogger implements StructuredLogger {

    private final List<String> capturedEvents = Collections.synchronizedList(new ArrayList<>());

    /**
     * Returns an unmodifiable snapshot of captured event names in emission order.
     *
     * @return list of event name strings
     */
    List<String> capturedEvents() {
        return Collections.unmodifiableList(new ArrayList<>(capturedEvents));
    }

    /** {@inheritDoc} Captures the event name. */
    @Override
    public void info(String event, Map<String, String> fields) {
        capturedEvents.add(event);
    }

    /** {@inheritDoc} Captures the event name. */
    @Override
    public void error(String event, String message) {
        capturedEvents.add(event);
    }

    /** {@inheritDoc} Captures the event name. */
    @Override
    public void warn(String event, String message) {
        capturedEvents.add(event);
    }
}
