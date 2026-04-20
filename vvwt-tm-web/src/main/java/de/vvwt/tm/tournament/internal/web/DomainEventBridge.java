package de.vvwt.tm.tournament.internal.web;

import de.vvwt.tm.tournament.events.DeviceRegisteredEvent;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges S09 Spring Application Events to connected WebSocket clients (E21S10,
 * AC-TDD-DomainEventBridge, AC-DOMAIN-EVENT-BRIDGE-INTEGRATION, AC-WEBSOCKET-BROKEN-CLIENT,
 * inventory row 450).
 *
 * <p>Reconstruction-in-place counterpart of {@link
 * de.vvwt.tm.infrastructure.web.DomainEventBridge}. During the parallel phase both coexist — the
 * legacy bridge listens to {@code de.vvwt.tm.domain.event.*} events; this bridge listens to the new
 * S09 public-API events at {@code de.vvwt.tm.tournament.events.*}. Atomic cutover at E21S13 removes
 * the legacy class and its event listeners simultaneously.
 *
 * <h2>DEC-21 package discipline</h2>
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.internal.web.*} per D-8 (tournament-internal
 * implementation). The {@link EventMessage} payload and topic constants are also internal; clients
 * consume the JSON wire format (not the Java type).
 *
 * <h2>Event listeners: @EventListener (not @TransactionalEventListener)</h2>
 *
 * <p>The S09 events ({@code de.vvwt.tm.tournament.events.*}) are published after the domain service
 * has already committed (the publishing call-site is at the service boundary, not inside the
 * transaction). Using {@code @EventListener} ensures delivery even in contexts where no transaction
 * is active (e.g., integration tests that publish directly via {@link
 * org.springframework.context.ApplicationEventPublisher}).
 *
 * <h2>AC-WEBSOCKET-BROKEN-CLIENT — broadcast failure isolation</h2>
 *
 * <p>If {@link SimpMessagingTemplate#convertAndSend} throws {@link MessagingException} (e.g., a
 * client disconnected mid-broadcast), the exception is caught and logged at WARN. It is NOT
 * re-thrown — broadcast failures must never propagate to the event publisher.
 *
 * <h2>Topics (AC-WEBSOCKET-CONFIG-INTEGRATION)</h2>
 *
 * <ul>
 *   <li>{@code /topic/events} — admin WebSocket topic (all four S09 event types)
 *   <li>{@code /topic/display/{tenantId}/events} — tenant-scoped display topic (all four S09 event
 *       types)
 * </ul>
 *
 * <h2>Bean name (parallel-phase discipline)</h2>
 *
 * <p>Named {@code "tmDomainEventBridge"} to avoid colliding with the legacy {@code
 * de.vvwt.tm.infrastructure.web.DomainEventBridge} during the parallel phase.
 *
 * @see EventMessage
 * @see WebSocketConfig
 * @see de.vvwt.tm.infrastructure.web.DomainEventBridge legacy counterpart (untouched)
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal vs public package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S10">E21S10 — inventory row 450</a>
 */
@Component("tmDomainEventBridge")
public class DomainEventBridge {

    private static final Logger log = LoggerFactory.getLogger(DomainEventBridge.class);

    /** Admin WebSocket topic (matches legacy exactly — AC-WEBSOCKET-CONFIG-INTEGRATION). */
    static final String EVENTS_TOPIC = "/topic/events";

    /** Tenant-scoped display topic pattern. Format: {@code /topic/display/{tenantId}/events}. */
    static final String DISPLAY_EVENTS_TOPIC_PATTERN = "/topic/display/%s/events";

    /** Event type constants (match legacy exactly — AC-WEBSOCKET-CONFIG-INTEGRATION). */
    static final String EVENT_TYPE_MATCH_RESULT_CHANGED = "MATCH_RESULT_CHANGED";

    static final String EVENT_TYPE_LAP_ADVANCED = "LAP_ADVANCED";
    static final String EVENT_TYPE_PHASE_STATUS_CHANGED = "PHASE_STATUS_CHANGED";
    static final String EVENT_TYPE_DEVICE_REGISTERED = "DEVICE_REGISTERED";

    private final SimpMessagingTemplate messagingTemplate;

    public DomainEventBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // =========================================================================
    // MatchResultChangedEvent — admin + display
    // =========================================================================

    /**
     * Forwards {@link MatchResultChangedEvent} to admin and tenant-scoped display topics.
     *
     * @param event the S09 domain event
     */
    @EventListener
    public void onMatchResultChanged(MatchResultChangedEvent event) {
        EventMessage message =
                new EventMessage(
                        EVENT_TYPE_MATCH_RESULT_CHANGED, event.getMatchId(), Instant.now());

        log.debug(
                "[tm-ws] Broadcasting {} matchId={} correlationId={}",
                EVENT_TYPE_MATCH_RESULT_CHANGED,
                event.getMatchId(),
                event.getCorrelationId());

        broadcastSafe(
                EVENTS_TOPIC,
                message,
                EVENT_TYPE_MATCH_RESULT_CHANGED,
                event.getMatchId(),
                event.getCorrelationId());
        broadcastSafe(
                displayTopic(event.getTenantId()),
                message,
                EVENT_TYPE_MATCH_RESULT_CHANGED,
                event.getMatchId(),
                event.getCorrelationId());
    }

    // =========================================================================
    // LapAdvancedEvent — display only
    // =========================================================================

    /**
     * Forwards {@link LapAdvancedEvent} to the tenant-scoped display topic.
     *
     * @param event the S09 domain event
     */
    @EventListener
    public void onLapAdvanced(LapAdvancedEvent event) {
        EventMessage message =
                new EventMessage(EVENT_TYPE_LAP_ADVANCED, event.getPhaseId(), Instant.now());

        log.debug(
                "[tm-ws] Broadcasting {} phaseId={} lap {} -> {} correlationId={}",
                EVENT_TYPE_LAP_ADVANCED,
                event.getPhaseId(),
                event.getPreviousLapNumber(),
                event.getNewLapNumber(),
                event.getCorrelationId());

        broadcastSafe(
                displayTopic(event.getTenantId()),
                message,
                EVENT_TYPE_LAP_ADVANCED,
                event.getPhaseId(),
                event.getCorrelationId());
    }

    // =========================================================================
    // PhaseStatusChangedEvent — display only
    // =========================================================================

    /**
     * Forwards {@link PhaseStatusChangedEvent} to the tenant-scoped display topic.
     *
     * @param event the S09 domain event
     */
    @EventListener
    public void onPhaseStatusChanged(PhaseStatusChangedEvent event) {
        EventMessage message =
                new EventMessage(
                        EVENT_TYPE_PHASE_STATUS_CHANGED, event.getPhaseId(), Instant.now());

        log.debug(
                "[tm-ws] Broadcasting {} phaseId={} {} -> {}",
                EVENT_TYPE_PHASE_STATUS_CHANGED,
                event.getPhaseId(),
                event.getPreviousStatus(),
                event.getNewStatus());

        broadcastSafe(
                displayTopic(event.getTenantId()),
                message,
                EVENT_TYPE_PHASE_STATUS_CHANGED,
                event.getPhaseId(),
                null);
    }

    // =========================================================================
    // DeviceRegisteredEvent — admin only
    // =========================================================================

    /**
     * Forwards {@link DeviceRegisteredEvent} to the admin topic.
     *
     * @param event the S09 domain event
     */
    @EventListener
    public void onDeviceRegistered(DeviceRegisteredEvent event) {
        EventMessage message =
                new EventMessage(EVENT_TYPE_DEVICE_REGISTERED, event.getDeviceId(), Instant.now());

        log.debug(
                "[tm-ws] Broadcasting {} deviceId={}",
                EVENT_TYPE_DEVICE_REGISTERED,
                event.getDeviceId());

        broadcastSafe(
                EVENTS_TOPIC, message, EVENT_TYPE_DEVICE_REGISTERED, event.getDeviceId(), null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the tenant-scoped display WebSocket topic for the given tenant.
     *
     * @param tenantId the tenant UUID
     * @return e.g. {@code /topic/display/abc-123.../events}
     */
    static String displayTopic(UUID tenantId) {
        return String.format(DISPLAY_EVENTS_TOPIC_PATTERN, tenantId);
    }

    /**
     * Broadcasts a message to the given topic, swallowing any {@link MessagingException}
     * (AC-WEBSOCKET-BROKEN-CLIENT).
     */
    private void broadcastSafe(
            String topic,
            EventMessage message,
            String eventType,
            Object entityId,
            Object correlationId) {
        try {
            messagingTemplate.convertAndSend(topic, message);
        } catch (MessagingException e) {
            log.warn(
                    "[tm-ws] Failed to broadcast {} to {} entityId={} correlationId={}: {}",
                    eventType,
                    topic,
                    entityId,
                    correlationId,
                    e.getMessage());
        }
    }
}
