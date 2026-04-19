package de.vvwt.tm.domain.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published when a phase status transitions (E07S06, AC5).
 *
 * <p>Published by {@link de.vvwt.tm.domain.PhasePreparationService} when:
 *
 * <ul>
 *   <li>A new phase is started: PENDING → ACTIVE
 *   <li>A phase is completed: ACTIVE → COMPLETED
 * </ul>
 *
 * <p>Display devices subscribed to the tenant-scoped WebSocket topic receive this event and
 * re-fetch the full phase overview from the display REST endpoints to re-render the Gesamtübersicht
 * layout for the new phase.
 *
 * <p>Published via {@link org.springframework.context.ApplicationEventPublisher} inside a
 * {@code @Transactional} method. Listeners with {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} fire after the transition is committed, ensuring display devices always see the
 * final committed state.
 *
 * @see de.vvwt.tm.domain.PhasePreparationService
 * @see MatchResultChangedEvent
 */
public class PhaseStatusChangedEvent extends ApplicationEvent {

    private final UUID tenantId;
    private final UUID tournamentId;
    private final UUID phaseId;
    private final String previousStatus;
    private final String newStatus;

    /**
     * Constructs a {@code PhaseStatusChangedEvent}.
     *
     * @param source the phase preparation service instance
     * @param tenantId the tenant in whose context the phase changed (for tenant-scoped WS topics)
     * @param tournamentId the tournament containing the phase
     * @param phaseId the phase whose status changed
     * @param previousStatus the phase status BEFORE the transition
     * @param newStatus the phase status AFTER the transition
     */
    public PhaseStatusChangedEvent(
            Object source,
            UUID tenantId,
            UUID tournamentId,
            UUID phaseId,
            String previousStatus,
            String newStatus) {
        super(source);
        this.tenantId = tenantId;
        this.tournamentId = tournamentId;
        this.phaseId = phaseId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }

    /**
     * @return the tenant in whose context the phase changed
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * @return the tournament containing the phase
     */
    public UUID getTournamentId() {
        return tournamentId;
    }

    /**
     * @return the phase whose status changed
     */
    public UUID getPhaseId() {
        return phaseId;
    }

    /**
     * @return the phase status before the transition
     */
    public String getPreviousStatus() {
        return previousStatus;
    }

    /**
     * @return the phase status after the transition
     */
    public String getNewStatus() {
        return newStatus;
    }

    @Override
    public String toString() {
        return "PhaseStatusChangedEvent{"
                + "phaseId="
                + phaseId
                + ", previousStatus='"
                + previousStatus
                + '\''
                + ", newStatus='"
                + newStatus
                + '\''
                + '}';
    }
}
