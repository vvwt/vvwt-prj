// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.events;

import de.vvwt.tm.tournament.MatchState;
import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published when a match result is registered or corrected (E21S09,
 * AC-TDD-MatchResultChangedEvent, AC-PKG-MatchResultChangedEvent).
 *
 * <p>This is the new public-API event at {@code de.vvwt.tm.tournament.events.*} per DEC-21 (D-8
 * package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.event.MatchResultChangedEvent} at atomic cutover time. During the
 * parallel-development phase, both coexist.
 *
 * <h2>MatchState dependency note (impl-report)</h2>
 *
 * <p>This class imports {@code de.vvwt.tm.domain.MatchState} (legacy location) because E21S05
 * (which would produce {@code de.vvwt.tm.tournament.MatchState} at the new coordinates) has not yet
 * been merged to staging at E21S09 delivery time. This import will be updated to {@code
 * de.vvwt.tm.tournament.MatchState} at E21S13 atomic cutover. This is a known dependency on the
 * legacy package that will be resolved by the atomic cutover story.
 *
 * <h2>MUST NOT carry sensitive payloads</h2>
 *
 * <p>This event MUST NOT embed credentials, session tokens, or raw SQL.
 *
 * @see de.vvwt.tm.domain.event.MatchResultChangedEvent legacy counterpart (untouched until cutover)
 */
public class MatchResultChangedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID tournamentId;
    private final UUID phaseId;
    private final UUID matchId;
    private final MatchState previousState;
    private final MatchState newState;
    private final String actorId;
    private final int previousLapNumber;
    private final int newLapNumber;
    private final UUID correlationId;

    /**
     * Constructs a {@code MatchResultChangedEvent}.
     *
     * @param source the object on which the event initially occurred (must not be {@code null})
     * @param tenantId the tenant in whose context the match result was registered
     * @param tournamentId the tournament this match belongs to
     * @param phaseId the phase this match belongs to
     * @param matchId the match whose result was registered or corrected
     * @param previousState the {@link MatchState} BEFORE the cascade ran
     * @param newState the {@link MatchState} AFTER the cascade ran
     * @param actorId the actor from the input (may be {@code null} in LAN mode)
     * @param previousLapNumber {@code phase.current_lap_number} BEFORE auto-advance
     * @param newLapNumber {@code phase.current_lap_number} AFTER auto-advance
     * @param correlationId random UUID for cross-step log correlation
     */
    public MatchResultChangedEvent(
            Object source,
            UUID tenantId,
            UUID tournamentId,
            UUID phaseId,
            UUID matchId,
            MatchState previousState,
            MatchState newState,
            String actorId,
            int previousLapNumber,
            int newLapNumber,
            UUID correlationId) {
        super(source);
        this.tenantId = tenantId;
        this.tournamentId = tournamentId;
        this.phaseId = phaseId;
        this.matchId = matchId;
        this.previousState = previousState;
        this.newState = newState;
        this.actorId = actorId;
        this.previousLapNumber = previousLapNumber;
        this.newLapNumber = newLapNumber;
        this.correlationId = correlationId;
    }

    /**
     * @return the tenant in whose context the match result was registered
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * @return the tournament this match belongs to
     */
    public UUID getTournamentId() {
        return tournamentId;
    }

    /**
     * @return the phase this match belongs to
     */
    public UUID getPhaseId() {
        return phaseId;
    }

    /**
     * @return the match whose result was registered or corrected
     */
    public UUID getMatchId() {
        return matchId;
    }

    /**
     * @return the {@link MatchState} before the cascade ran
     */
    public MatchState getPreviousState() {
        return previousState;
    }

    /**
     * @return the {@link MatchState} after the cascade ran
     */
    public MatchState getNewState() {
        return newState;
    }

    /**
     * @return the actor identifier; may be {@code null} in LAN mode
     */
    public String getActorId() {
        return actorId;
    }

    /**
     * @return {@code phase.current_lap_number} before auto-advance
     */
    public int getPreviousLapNumber() {
        return previousLapNumber;
    }

    /**
     * @return {@code phase.current_lap_number} after auto-advance
     */
    public int getNewLapNumber() {
        return newLapNumber;
    }

    /**
     * @return random UUID for cross-step log correlation
     */
    public UUID getCorrelationId() {
        return correlationId;
    }

    @Override
    public String toString() {
        return "MatchResultChangedEvent{"
                + "matchId="
                + matchId
                + ", previousState="
                + previousState
                + ", newState="
                + newState
                + ", correlationId="
                + correlationId
                + '}';
    }
}
