package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.PhaseLifecycleService;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import de.vvwt.tm.domain.event.PhaseCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;

/**
 * Bridges Spring Application Events from E03 domain services to connected WebSocket clients
 * (AC5, E05S03).
 *
 * <h2>Design (AC5)</h2>
 * <p>Listens to {@link MatchResultChangedEvent} (from {@link de.vvwt.tm.domain.CascadeRecomputeService}
 * step 12 per E03S11) using {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. This
 * guarantees that WebSocket clients always see committed state — they can safely call REST
 * endpoints immediately upon receiving a notification.
 *
 * <h2>Decoupling (AC8)</h2>
 * <p>If broadcasting fails (e.g., the client disconnected between the subscription check and
 * the send), the {@link MessagingException} is caught and logged at {@code WARN} level. The
 * exception is NOT re-thrown. This ensures broadcast failures do not propagate to the
 * committing transaction (which has already committed by the time AFTER_COMMIT listeners run).
 *
 * <h2>Extensibility (AC5 notes)</h2>
 * <p>To add a new event type, add a new {@code @TransactionalEventListener} method in this
 * class. No changes to {@link WebSocketConfig} or {@link EventMessage} are required.
 *
 * <h2>No TenantContext required (AC3)</h2>
 * <p>This component does not perform any repository access — it only broadcasts an
 * {@link EventMessage} to connected clients. The {@link de.vvwt.tm.domain.repo.TenantContext}
 * ThreadLocal is NOT set on the messaging broker's thread; setting it here would be wrong
 * (no repo calls are made). The SPA fetches full data via REST after receiving the notification.
 *
 * <h2>WebSocket topic (AC4)</h2>
 * <p>Messages are broadcast to {@code /topic/events}. The SPA subscribes to this topic
 * during STOMP session setup.
 *
 * @see WebSocketConfig
 * @see EventMessage
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@Component
public class DomainEventBridge {

    private static final Logger log = LoggerFactory.getLogger(DomainEventBridge.class);

    /** WebSocket topic to which all domain event notifications are broadcast (AC4, AC5). */
    static final String EVENTS_TOPIC = "/topic/events";

    /** Event type constant for {@link MatchResultChangedEvent} (AC5). */
    static final String EVENT_TYPE_MATCH_RESULT_CHANGED = "MATCH_RESULT_CHANGED";

    /** Event type broadcast when a lap auto-advances (AC5 — E05S10). entityId = phaseId. */
    static final String EVENT_TYPE_LAP_ADVANCED = "LAP_ADVANCED";

    /** Event type broadcast when a phase transitions to COMPLETED (AC5 — E05S10). entityId = phaseId. */
    static final String EVENT_TYPE_PHASE_COMPLETED = "PHASE_COMPLETED";

    private final SimpMessagingTemplate messagingTemplate;
    private final PhaseLifecycleService phaseLifecycleService;

    /**
     * @param messagingTemplate    Spring's WebSocket messaging template for broadcasting
     *                             messages to subscribed clients
     * @param phaseLifecycleService for tournament completion check (AC11 — E05S07)
     */
    public DomainEventBridge(SimpMessagingTemplate messagingTemplate,
                              PhaseLifecycleService phaseLifecycleService) {
        this.messagingTemplate = messagingTemplate;
        this.phaseLifecycleService = phaseLifecycleService;
    }

    /**
     * Forwards {@link MatchResultChangedEvent} to connected WebSocket clients after
     * the originating transaction commits (AC5, AC8).
     *
     * <p>The {@link EventMessage} contains only the event type, match UUID, and timestamp.
     * The SPA calls {@code GET /api/matches/{matchId}} after receiving this notification
     * to fetch the updated match state (AC10 — WebSocket is a notification channel only).
     *
     * <p>Broadcasting errors are caught and logged at WARN — never rethrown (AC8).
     *
     * @param event the domain event from {@link de.vvwt.tm.domain.CascadeRecomputeService}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchResultChanged(MatchResultChangedEvent event) {
        EventMessage message = new EventMessage(
                EVENT_TYPE_MATCH_RESULT_CHANGED,
                event.getMatchId(),
                Instant.now());

        log.debug("[tm-ws] Broadcasting {} for matchId={} correlationId={}",
                EVENT_TYPE_MATCH_RESULT_CHANGED, event.getMatchId(), event.getCorrelationId());

        try {
            messagingTemplate.convertAndSend(EVENTS_TOPIC, message);
        } catch (MessagingException e) {
            // AC8: broadcast failure must NOT propagate to the transaction.
            // Client may have disconnected between subscription check and send — this is expected.
            log.warn("[tm-ws] Failed to broadcast {} for matchId={}: {}",
                    EVENT_TYPE_MATCH_RESULT_CHANGED, event.getMatchId(), e.getMessage());
        }

        // AC5 (E05S10): broadcast LAP_ADVANCED when the cascade auto-advances a lap
        if (event.getNewLapNumber() > event.getPreviousLapNumber() && event.getPhaseId() != null) {
            EventMessage lapMsg = new EventMessage(
                    EVENT_TYPE_LAP_ADVANCED,
                    event.getPhaseId(),
                    Instant.now());

            log.debug("[tm-ws] Broadcasting {} phaseId={} lap {} → {}",
                    EVENT_TYPE_LAP_ADVANCED, event.getPhaseId(),
                    event.getPreviousLapNumber(), event.getNewLapNumber());

            try {
                messagingTemplate.convertAndSend(EVENTS_TOPIC, lapMsg);
            } catch (MessagingException e) {
                log.warn("[tm-ws] Failed to broadcast {}: {}", EVENT_TYPE_LAP_ADVANCED, e.getMessage());
            }
        }

        // AC5 (E05S10) + AC11 (E05S07): check if this cascade result completed the phase,
        // which triggers phase → COMPLETED → tournament → COMPLETED chain.
        // checkPhaseCompletion runs in a NEW transaction (AFTER_COMMIT context).
        if (event.getPhaseId() != null && event.getTournamentId() != null) {
            try {
                phaseLifecycleService.checkPhaseCompletion(event.getPhaseId(), event.getTournamentId());
            } catch (Exception e) {
                log.warn("[tm-ws] checkPhaseCompletion failed for phase={}: {}",
                        event.getPhaseId(), e.getMessage());
            }
        }
    }

    /**
     * Forwards {@link PhaseCompletedEvent} to connected WebSocket clients after the originating
     * transaction commits (AC5 — E05S10).
     *
     * <p>The {@link EventMessage} contains the {@code PHASE_COMPLETED} event type and the phaseId
     * as the entity identifier. The SPA uses this to trigger a final group-table refresh.
     *
     * @param event the domain event from {@link PhaseLifecycleService#checkPhaseCompletion}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhaseCompleted(PhaseCompletedEvent event) {
        EventMessage message = new EventMessage(
                EVENT_TYPE_PHASE_COMPLETED,
                event.getPhaseId(),
                Instant.now());

        log.debug("[tm-ws] Broadcasting {} phaseId={}", EVENT_TYPE_PHASE_COMPLETED, event.getPhaseId());

        try {
            messagingTemplate.convertAndSend(EVENTS_TOPIC, message);
        } catch (MessagingException e) {
            log.warn("[tm-ws] Failed to broadcast {} for phaseId={}: {}",
                    EVENT_TYPE_PHASE_COMPLETED, event.getPhaseId(), e.getMessage());
        }
    }
}
