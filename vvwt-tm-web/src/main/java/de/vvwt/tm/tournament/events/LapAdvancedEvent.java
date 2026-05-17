// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.events;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published when the auto-advance logic increments the current lap
 * number (E21S09, AC-TDD-LapAdvancedEvent, AC-PKG-LapAdvancedEvent).
 *
 * <p>This is the new public-API event at {@code de.vvwt.tm.tournament.events.*} per DEC-21 (D-8
 * package discipline). It replaces the legacy {@code de.vvwt.tm.domain.event.LapAdvancedEvent} at
 * atomic cutover time. During the parallel-development phase, both coexist.
 *
 * <p>This event is published only when a real lap advance occurs ({@code newLapNumber > 0 &&
 * previousLapNumber != newLapNumber}). No event is published on last-lap sentinel writes (DEC-65:
 * {@code newLapNumber == 0}) or when no advance occurs. {@code previousLapNumber} and {@code
 * newLapNumber} follow the 1-based running-lap index (DEC-65): 0 = sentinel "no lap running".
 *
 * <h2>MUST NOT carry sensitive payloads</h2>
 *
 * <p>This event MUST NOT embed credentials, session tokens, or raw SQL.
 *
 * @see de.vvwt.tm.domain.event.LapAdvancedEvent legacy counterpart (untouched until cutover)
 */
public class LapAdvancedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID tournamentId;
    private final UUID phaseId;
    private final int previousLapNumber;
    private final int newLapNumber;
    private final UUID correlationId;

    /**
     * Constructs a {@code LapAdvancedEvent}.
     *
     * @param source the object on which the event initially occurred (must not be {@code null})
     * @param tenantId the tenant in whose context the lap advanced
     * @param tournamentId the tournament in which the lap advanced
     * @param phaseId the phase in which the lap advanced
     * @param previousLapNumber the lap number BEFORE the auto-advance
     * @param newLapNumber the lap number AFTER the auto-advance
     * @param correlationId the cascade correlation ID for log tracing
     */
    public LapAdvancedEvent(
            Object source,
            UUID tenantId,
            UUID tournamentId,
            UUID phaseId,
            int previousLapNumber,
            int newLapNumber,
            UUID correlationId) {
        super(source);
        this.tenantId = tenantId;
        this.tournamentId = tournamentId;
        this.phaseId = phaseId;
        this.previousLapNumber = previousLapNumber;
        this.newLapNumber = newLapNumber;
        this.correlationId = correlationId;
    }

    /**
     * @return the tenant in whose context the lap advanced
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * @return the tournament in which the lap advanced
     */
    public UUID getTournamentId() {
        return tournamentId;
    }

    /**
     * @return the phase in which the lap advanced
     */
    public UUID getPhaseId() {
        return phaseId;
    }

    /**
     * @return the lap number before the auto-advance
     */
    public int getPreviousLapNumber() {
        return previousLapNumber;
    }

    /**
     * @return the lap number after the auto-advance
     */
    public int getNewLapNumber() {
        return newLapNumber;
    }

    /**
     * @return the cascade correlation ID
     */
    public UUID getCorrelationId() {
        return correlationId;
    }

    @Override
    public String toString() {
        return "LapAdvancedEvent{"
                + "phaseId="
                + phaseId
                + ", previousLapNumber="
                + previousLapNumber
                + ", newLapNumber="
                + newLapNumber
                + ", correlationId="
                + correlationId
                + '}';
    }
}
