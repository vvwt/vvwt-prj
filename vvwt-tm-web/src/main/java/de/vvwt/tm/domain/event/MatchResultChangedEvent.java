package de.vvwt.tm.domain.event;

import de.vvwt.tm.domain.MatchState;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Spring Application Event published by {@link de.vvwt.tm.domain.CascadeRecomputeService}
 * at cascade step 12 (D-21, E03S11, AC14).
 *
 * <p>The event is published via {@link org.springframework.context.ApplicationEventPublisher}
 * inside the {@code @Transactional} method. Spring defers actual listener invocation until
 * after the transaction commits when listeners are annotated with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 * This guarantees that subscribers (e.g., E03S13 RoundSnapshotService, E07 Gesamtübersicht)
 * always see committed state when they query.
 *
 * <h2>Lap-advance fields</h2>
 * <p>{@code previousLapNumber} captures {@code phase.current_lap_number} BEFORE step 10's
 * auto-advance; {@code newLapNumber} captures the value AFTER step 10. When no lap advance
 * occurred, {@code previousLapNumber == newLapNumber}.
 *
 * <h2>correlationId</h2>
 * <p>A random UUID generated once per cascade invocation for cross-step log correlation (AC25).
 * Subscribers may include this in their own log entries for end-to-end traceability.
 *
 * @see de.vvwt.tm.domain.CascadeRecomputeService
 */
public class MatchResultChangedEvent extends ApplicationEvent {

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
     * @param source             the object on which the event initially occurred (the cascade service)
     * @param tournamentId       the tournament this match belongs to
     * @param phaseId            the phase this match belongs to
     * @param matchId            the match whose result was registered or corrected
     * @param previousState      the {@link MatchState} BEFORE cascade step 6
     * @param newState           the {@link MatchState} AFTER cascade step 6
     * @param actorId            the actor from the input (may be {@code null} in LAN mode)
     * @param previousLapNumber  {@code phase.current_lap_number} BEFORE step 10
     * @param newLapNumber       {@code phase.current_lap_number} AFTER step 10
     * @param correlationId      random UUID for cross-step log correlation (AC25)
     */
    public MatchResultChangedEvent(Object source,
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

    /** @return the tournament this match belongs to */
    public UUID getTournamentId() { return tournamentId; }

    /** @return the phase this match belongs to */
    public UUID getPhaseId() { return phaseId; }

    /** @return the match whose result was registered or corrected */
    public UUID getMatchId() { return matchId; }

    /** @return the {@link MatchState} before the cascade ran */
    public MatchState getPreviousState() { return previousState; }

    /** @return the {@link MatchState} after cascade step 6 (may equal previous if no state change) */
    public MatchState getNewState() { return newState; }

    /** @return the actor identifier from the input; may be {@code null} in LAN mode */
    public String getActorId() { return actorId; }

    /** @return {@code phase.current_lap_number} before step 10's auto-advance */
    public int getPreviousLapNumber() { return previousLapNumber; }

    /** @return {@code phase.current_lap_number} after step 10 (same as previous if no advance) */
    public int getNewLapNumber() { return newLapNumber; }

    /** @return random UUID for cross-step log correlation (AC25) */
    public UUID getCorrelationId() { return correlationId; }

    @Override
    public String toString() {
        return "MatchResultChangedEvent{"
                + "matchId=" + matchId
                + ", previousState=" + previousState
                + ", newState=" + newState
                + ", previousLapNumber=" + previousLapNumber
                + ", newLapNumber=" + newLapNumber
                + ", correlationId=" + correlationId
                + '}';
    }
}
