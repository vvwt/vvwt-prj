package de.vvwt.tm.tournament.events;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published when a phase status transitions (E21S09,
 * AC-TDD-PhaseStatusChangedEvent, AC-PKG-PhaseStatusChangedEvent).
 *
 * <p>This is the new public-API event at {@code de.vvwt.tm.tournament.events.*} per DEC-21 (D-8
 * package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.event.PhaseStatusChangedEvent} at atomic cutover time. During the
 * parallel-development phase, both coexist.
 *
 * <p>Published when:
 *
 * <ul>
 *   <li>A new phase is started: PENDING → ACTIVE
 *   <li>A phase is completed: ACTIVE → COMPLETED
 * </ul>
 *
 * <h2>MUST NOT carry sensitive payloads</h2>
 *
 * <p>This event MUST NOT embed credentials, session tokens, or raw SQL.
 *
 * @see de.vvwt.tm.domain.event.PhaseStatusChangedEvent legacy counterpart (untouched until cutover)
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
     * @param source the object on which the event initially occurred (must not be {@code null})
     * @param tenantId the tenant in whose context the phase changed
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
