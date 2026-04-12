package de.vvwt.tm.domain.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Spring Application Event published by {@link de.vvwt.tm.domain.PhaseLifecycleService}
 * when a phase transitions to {@code COMPLETED} status.
 *
 * <p>Published via {@link org.springframework.context.ApplicationEventPublisher} inside the
 * {@code @Transactional} method {@code advanceLap}. Listened to by
 * {@link de.vvwt.tm.infrastructure.web.DomainEventBridge} which broadcasts a
 * {@code PHASE_COMPLETED} WebSocket notification to connected admin clients.
 *
 * <h2>Event flow (AC5 — E05S10)</h2>
 * <ol>
 *   <li>All matches in the last lap are finished → lap auto-advances in cascade step 10.</li>
 *   <li>Phase transitions ACTIVE → COMPLETED.</li>
 *   <li>This event is published INSIDE the transaction (before commit).</li>
 *   <li>After commit, {@link de.vvwt.tm.infrastructure.web.DomainEventBridge} broadcasts
 *       {@code PHASE_COMPLETED} to subscribed WebSocket clients.</li>
 *   <li>The SPA re-fetches group tables and phase status to reflect the completed state.</li>
 * </ol>
 *
 * @see de.vvwt.tm.domain.PhaseLifecycleService
 * @see de.vvwt.tm.infrastructure.web.DomainEventBridge
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
public class PhaseCompletedEvent extends ApplicationEvent {

    private final UUID tournamentId;
    private final UUID phaseId;

    /**
     * Constructs a {@code PhaseCompletedEvent}.
     *
     * @param source       the object on which the event initially occurred (the lifecycle service)
     * @param tournamentId the tournament this phase belongs to
     * @param phaseId      the phase that transitioned to COMPLETED
     */
    public PhaseCompletedEvent(Object source, UUID tournamentId, UUID phaseId) {
        super(source);
        this.tournamentId = tournamentId;
        this.phaseId = phaseId;
    }

    /** @return the tournament this phase belongs to */
    public UUID getTournamentId() { return tournamentId; }

    /** @return the phase that transitioned to COMPLETED */
    public UUID getPhaseId() { return phaseId; }
}
