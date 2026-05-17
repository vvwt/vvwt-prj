// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.HostActivityProbe;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link HostActivityProbe} (E63S04).
 *
 * <p>Tracks in-memory whether any TM phase is currently in the {@code ACTIVE} state using Spring
 * {@link PhaseStatusChangedEvent}s. The counter increments when a phase transitions TO {@code
 * ACTIVE} and decrements when a phase transitions FROM {@code ACTIVE} to any other status.
 *
 * <h2>Design rationale</h2>
 *
 * <p>The embedded worker runs on a background thread without tenant context, so DB-level queries
 * are not feasible. In-memory tracking via application events provides a lightweight, zero-DB read
 * signal. The counter approach handles multiple concurrent active phases across tenants correctly.
 *
 * <h2>DEC-64 / C-5 compliance</h2>
 *
 * <p>This in-process read is the permitted host-coupling channel for the embedded worker. The
 * worker ONLY reads a boolean; no team UUIDs, PII, or DB connections cross this boundary.
 *
 * <h2>DEC-58 / DEC-72</h2>
 *
 * <p>{@code @Component} annotated: public {@link HostActivityProbe} interface in the
 * bounded-context root package; {@code Default*} implementation in {@code .internal}. DEC-72 Clause
 * B covers {@code @Component} beans.
 *
 * <p>Story: E63S04 — AC-GOV-HOST-ACTIVITY-PROBE-INTERFACE.
 */
@Component
class DefaultHostActivityProbe implements HostActivityProbe {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHostActivityProbe.class);

    /**
     * Counter of currently ACTIVE phases across all tenants. Positive means at least one phase is
     * actively being scored.
     */
    private final AtomicInteger activePhaseCount = new AtomicInteger(0);

    /**
     * Listens for phase status change events to maintain the in-memory active-phase counter.
     *
     * <p>Increments when a phase transitions TO {@code ACTIVE}; decrements when a phase transitions
     * FROM {@code ACTIVE} to any other status. The counter is clamped to zero to guard against
     * event ordering anomalies at startup.
     *
     * @param event the phase status changed event (from {@code tournament::events})
     */
    @EventListener
    void onPhaseStatusChanged(PhaseStatusChangedEvent event) {
        String prev = event.getPreviousStatus();
        String next = event.getNewStatus();

        if ("ACTIVE".equals(next) && !"ACTIVE".equals(prev)) {
            int count = activePhaseCount.incrementAndGet();
            LOG.debug(
                    "DefaultHostActivityProbe: phase {} → ACTIVE (activeCount={})",
                    event.getPhaseId(),
                    count);
        } else if ("ACTIVE".equals(prev) && !"ACTIVE".equals(next)) {
            int count = activePhaseCount.updateAndGet(c -> Math.max(0, c - 1));
            LOG.debug(
                    "DefaultHostActivityProbe: phase {} left ACTIVE → {} (activeCount={})",
                    event.getPhaseId(),
                    next,
                    count);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} if at least one TM phase is in the {@code ACTIVE} status (live
     * scoring in progress). Returns {@code false} when no phases are active (safe for the worker to
     * solve packets).
     */
    @Override
    public boolean isLiveScoringActive() {
        return activePhaseCount.get() > 0;
    }
}
