package de.vvwt.tm.domain.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published by {@link de.vvwt.tm.domain.CascadeRecomputeService} when the
 * auto-advance logic at cascade step 10 increments the current lap number (E07S06, AC4).
 *
 * <p>This event is published only when a real lap advance occurs — i.e., when {@code
 * previousLapNumber != newLapNumber} after the cascade. When no advance occurs, this event is NOT
 * published.
 *
 * <p>Consumers (e.g., {@link de.vvwt.tm.infrastructure.web.DomainEventBridge}) use this event to
 * notify display devices that they should refresh their match grid to show the new lap.
 *
 * <p>Published via {@link org.springframework.context.ApplicationEventPublisher} inside the
 * {@code @Transactional} cascade method. Listeners annotated with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} only fire after commit, ensuring
 * display devices always see committed state.
 *
 * @see de.vvwt.tm.domain.CascadeRecomputeService
 * @see MatchResultChangedEvent
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
     * @param source the cascade service instance
     * @param tenantId the tenant in whose context the lap advanced (for tenant-scoped WS topics)
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
