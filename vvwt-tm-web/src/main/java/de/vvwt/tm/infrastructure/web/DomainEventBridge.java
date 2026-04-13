package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.event.DeviceRegisteredEvent;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;

/**
 * Bridges Spring Application Events from domain services to connected WebSocket clients
 * (E05S03, E06S05).
 *
 * <h2>Design (AC5 — E05S03)</h2>
 * <p>Listens to {@link MatchResultChangedEvent} (from {@link de.vvwt.tm.domain.CascadeRecomputeService}
 * step 12 per E03S11) using {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. This
 * guarantees that WebSocket clients always see committed state — they can safely call REST
 * endpoints immediately upon receiving a notification.
 *
 * <h2>Design (E06S05-AC6 — device registration)</h2>
 * <p>Listens to {@link DeviceRegisteredEvent} (from {@link de.vvwt.tm.domain.DeviceService})
 * using {@code @EventListener}. Device registration is not wrapped in a transaction at the
 * service layer in V1 (direct repository save), so a plain {@code @EventListener} is used
 * rather than {@code @TransactionalEventListener}. The admin SPA reacts to the
 * {@code DEVICE_REGISTERED} event type by refreshing its device list.
 *
 * <h2>Decoupling (AC8)</h2>
 * <p>If broadcasting fails (e.g., the client disconnected between the subscription check and
 * the send), the {@link MessagingException} is caught and logged at {@code WARN} level. The
 * exception is NOT re-thrown. This ensures broadcast failures do not propagate to the
 * committing transaction (which has already committed by the time AFTER_COMMIT listeners run).
 *
 * <h2>Extensibility</h2>
 * <p>To add a new event type, add a new listener method in this class.
 * No changes to {@link WebSocketConfig} or {@link EventMessage} are required.
 *
 * <h2>No TenantContext required</h2>
 * <p>This component does not perform any repository access — it only broadcasts an
 * {@link EventMessage} to connected clients. The {@link de.vvwt.tm.domain.repo.TenantContext}
 * ThreadLocal is NOT set on the messaging broker's thread; setting it here would be wrong
 * (no repo calls are made). The SPA fetches full data via REST after receiving the notification.
 *
 * <h2>WebSocket topic</h2>
 * <p>Messages are broadcast to {@code /topic/events}. The SPA subscribes to this topic
 * during STOMP session setup.
 *
 * @see WebSocketConfig
 * @see EventMessage
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S05.story.md">Story E06S05</a>
 */
@Component
public class DomainEventBridge {

    private static final Logger log = LoggerFactory.getLogger(DomainEventBridge.class);

    /** WebSocket topic to which all domain event notifications are broadcast (AC4, AC5). */
    static final String EVENTS_TOPIC = "/topic/events";

    /** Event type constant for {@link MatchResultChangedEvent} (E05S03-AC5). */
    static final String EVENT_TYPE_MATCH_RESULT_CHANGED = "MATCH_RESULT_CHANGED";

    /** Event type constant for {@link DeviceRegisteredEvent} (E06S05-AC6). */
    static final String EVENT_TYPE_DEVICE_REGISTERED = "DEVICE_REGISTERED";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @param messagingTemplate Spring's WebSocket messaging template for broadcasting
     *                          messages to subscribed clients
     */
    public DomainEventBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
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
    }

    /**
     * Forwards {@link DeviceRegisteredEvent} to connected WebSocket clients (E06S05-AC6).
     *
     * <p>The admin SPA reacts to {@code DEVICE_REGISTERED} by calling
     * {@code GET /api/devices/list} to refresh the device management view. No device data
     * is embedded in the notification (security: only the device UUID is exposed, consistent
     * with the REST API).
     *
     * <p>Uses {@code @EventListener} (not {@code @TransactionalEventListener}) because
     * device registration is not wrapped in a transaction in V1 — the event fires
     * synchronously after the repository save.
     *
     * <p>Broadcasting errors are caught and logged at WARN — never rethrown.
     *
     * @param event the domain event from {@link de.vvwt.tm.domain.DeviceService}
     */
    @EventListener
    public void onDeviceRegistered(DeviceRegisteredEvent event) {
        EventMessage message = new EventMessage(
                EVENT_TYPE_DEVICE_REGISTERED,
                event.getDeviceId(),
                Instant.now());

        log.debug("[tm-ws] Broadcasting {} for deviceId={}", EVENT_TYPE_DEVICE_REGISTERED,
                event.getDeviceId());

        try {
            messagingTemplate.convertAndSend(EVENTS_TOPIC, message);
        } catch (MessagingException e) {
            // Broadcast failure must NOT propagate — client may have disconnected.
            log.warn("[tm-ws] Failed to broadcast {} for deviceId={}: {}",
                    EVENT_TYPE_DEVICE_REGISTERED, event.getDeviceId(), e.getMessage());
        }
    }
}
