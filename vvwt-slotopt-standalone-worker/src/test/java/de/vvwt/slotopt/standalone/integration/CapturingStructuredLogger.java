// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.integration;

import de.vvwt.slotopt.standalone.log.StructuredLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-support implementation of {@link StructuredLogger} that captures emitted event names.
 *
 * <p>Used by {@link WorkerLauncher} to collect runtime observability events from the worker loop.
 * The event name (first argument to {@link #info}, {@link #error}, {@link #warn}) is captured for
 * assertion in integration tests.
 *
 * <p>Supports deterministic readiness waiting via {@link #awaitEvent(String, long)}: a {@link
 * CountDownLatch} registered for a target event name is counted down the first time that event is
 * observed, unblocking any caller awaiting readiness (e.g. a test waiting for {@code packet_pulled}
 * before requesting shutdown — E41S08 timing-race fix).
 *
 * <p>DEC-36: implementation in the {@code integration} test package; references the public
 * interface {@link StructuredLogger} (not the {@code DefaultStructuredLogger} implementation).
 *
 * <p>Story: E41S06 AC-OBSERVABILITY-EVENT-MATRIX; E41S08 AC2 deterministic readiness gate.
 */
class CapturingStructuredLogger implements StructuredLogger {

    private final List<String> capturedEvents = Collections.synchronizedList(new ArrayList<>());

    /**
     * Latches registered via {@link #awaitEvent}: event name → latch. Counted down (once) when the
     * matching event is first captured.
     */
    private final ConcurrentHashMap<String, CountDownLatch> eventLatches =
            new ConcurrentHashMap<>();

    /**
     * Registers a {@link CountDownLatch} for the given event name and blocks until that event is
     * observed (or the timeout elapses).
     *
     * <p>If the event has already been captured before this method is called, the latch counts down
     * immediately. Otherwise, the next emission of the event unblocks this call.
     *
     * <p>Used by {@link WorkerLauncher#awaitFirstRuntimeEvent} to replace the fixed {@code
     * Thread.sleep} readiness gate with a deterministic signal (E41S08).
     *
     * @param eventName the event name to wait for (e.g. {@code "packet_pulled"})
     * @param timeoutMs maximum wait time in milliseconds
     * @return {@code true} if the event was observed within the timeout; {@code false} otherwise
     * @throws InterruptedException if the waiting thread is interrupted
     */
    boolean awaitEvent(String eventName, long timeoutMs) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        eventLatches.put(eventName, latch);

        // Check if the event was already captured before the latch was registered.
        if (capturedEvents.contains(eventName)) {
            latch.countDown();
        }

        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns an unmodifiable snapshot of captured event names in emission order.
     *
     * @return list of event name strings
     */
    List<String> capturedEvents() {
        return Collections.unmodifiableList(new ArrayList<>(capturedEvents));
    }

    /** {@inheritDoc} Captures the event name and counts down any registered latch. */
    @Override
    public void info(String event, Map<String, String> fields) {
        capturedEvents.add(event);
        countDownIfRegistered(event);
    }

    /** {@inheritDoc} Captures the event name and counts down any registered latch. */
    @Override
    public void error(String event, String message) {
        capturedEvents.add(event);
        countDownIfRegistered(event);
    }

    /** {@inheritDoc} Captures the event name and counts down any registered latch. */
    @Override
    public void warn(String event, String message) {
        capturedEvents.add(event);
        countDownIfRegistered(event);
    }

    private void countDownIfRegistered(String event) {
        CountDownLatch latch = eventLatches.get(event);
        if (latch != null) {
            latch.countDown();
        }
    }
}
