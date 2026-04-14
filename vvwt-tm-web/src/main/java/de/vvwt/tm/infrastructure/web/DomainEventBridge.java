package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.event.DeviceRegisteredEvent;
import de.vvwt.tm.domain.event.LapAdvancedEvent;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import de.vvwt.tm.domain.event.PhaseStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Instant;
import java.util.UUID;

/**
 * Bridges Spring Application Events from domain services to connected WebSocket clients
 * (E05S03, E06S05, E07S06).
 *
 * <h2>Original design (E05S03)</h2>
 * <p>Broadcasts {@link MatchResultChangedEvent} to the admin topic {@code /topic/events}.
 * Admin clients authenticate via HTTP Basic (see {@link WebSocketSecurityConfig}).
 *
 * <h2>E06S05 extension: device registration</h2>
 * <p>Listens to {@link DeviceRegisteredEvent} (from {@link de.vvwt.tm.domain.DeviceService})
 * using {@code @EventListener}. The admin SPA reacts to the {@code DEVICE_REGISTERED} event
 * type by refreshing its device list.
 *
 * <h2>E07S06 extensions: tenant-scoped display topics (AC1–AC5, AC11, AC12)</h2>
 * <p>Three events are broadcast to a tenant-scoped display topic
 * {@code /topic/display/{tenantId}/events}:
 * <ul>
 *   <li>{@link MatchResultChangedEvent} — AC2, AC3: match result changed</li>
 *   <li>{@link LapAdvancedEvent} — AC4: current lap advanced</li>
 *   <li>{@link PhaseStatusChangedEvent} — AC5: phase transitioned</li>
 * </ul>
 *
 * <p>The tenant-scoped topic ensures a display device subscribed to tenant A's topic never
 * receives events from tenant B (AC11). The device-token interceptor in
 * {@link WebSocketSecurityConfig} ensures each device can only subscribe to its own tenant's topic.
 *
 * <h2>Decoupling (AC8)</h2>
 * <p>If broadcasting fails, the {@link MessagingException} is caught and logged at {@code WARN}.
 * It is NOT re-thrown — broadcast failures must not propagate to the committed transaction.
 *
 * <h2>Security — read-only display topics (AC12)</h2>
 * <p>Display topics are under {@code /topic/display/...} — Spring STOMP only routes SUBSCRIBEs
 * to {@code /topic/**}. No SEND frames from display clients can target these topics via the
 * {@code /app} prefix. Display devices are structurally read-only at the WebSocket layer.
 *
 * @see WebSocketConfig
 * @see WebSocketSecurityConfig
 * @see EventMessage
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S05.story.md">Story E06S05</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S06.story.md">Story E07S06</a>
 */
@Component
public class DomainEventBridge {

    private static final Logger log = LoggerFactory.getLogger(DomainEventBridge.class);

    /** Admin WebSocket topic (E05S03 — original, unchanged). */
    static final String EVENTS_TOPIC = "/topic/events";

    /**
     * Tenant-scoped display topic pattern (E07S06 AC1, AC11).
     * Format: {@code /topic/display/{tenantId}/events}.
     */
    static final String DISPLAY_EVENTS_TOPIC_PATTERN = "/topic/display/%s/events";

    /** Event type constants. */
    static final String EVENT_TYPE_MATCH_RESULT_CHANGED = "MATCH_RESULT_CHANGED";
    static final String EVENT_TYPE_LAP_ADVANCED         = "LAP_ADVANCED";
    static final String EVENT_TYPE_PHASE_STATUS_CHANGED = "PHASE_STATUS_CHANGED";

    /** Event type constant for {@link DeviceRegisteredEvent} (E06S05-AC6). */
    static final String EVENT_TYPE_DEVICE_REGISTERED = "DEVICE_REGISTERED";

    private final SimpMessagingTemplate messagingTemplate;

    public DomainEventBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // =========================================================================
    // MatchResultChangedEvent — admin + display (E05S03, E07S06 AC2/AC3)
    // =========================================================================

    /**
     * Forwards {@link MatchResultChangedEvent} to admin WebSocket clients after commit (E05S03 AC5).
     * Also broadcasts to the tenant-scoped display topic (E07S06 AC2, AC3).
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

        // Admin topic (E05S03 — unchanged)
        broadcastSafe(EVENTS_TOPIC, message,
                EVENT_TYPE_MATCH_RESULT_CHANGED, event.getMatchId(), event.getCorrelationId());

        // E07S06 AC2/AC3: Tenant-scoped display topic
        broadcastSafe(displayTopic(event.getTenantId()), message,
                EVENT_TYPE_MATCH_RESULT_CHANGED, event.getMatchId(), event.getCorrelationId());
    }

    // =========================================================================
    // LapAdvancedEvent — display only (E07S06 AC4)
    // =========================================================================

    /**
     * Forwards {@link LapAdvancedEvent} to tenant-scoped display clients after commit (E07S06 AC4).
     *
     * <p>Only published when a real lap advance occurred ({@code previousLap != newLap},
     * guarded in {@link de.vvwt.tm.domain.CascadeRecomputeService}).
     *
     * @param event the lap-advanced event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLapAdvanced(LapAdvancedEvent event) {
        EventMessage message = new EventMessage(
                EVENT_TYPE_LAP_ADVANCED,
                event.getPhaseId(),
                Instant.now());

        log.debug("[tm-ws] Broadcasting {} for phaseId={} lap {} -> {} correlationId={}",
                EVENT_TYPE_LAP_ADVANCED, event.getPhaseId(),
                event.getPreviousLapNumber(), event.getNewLapNumber(), event.getCorrelationId());

        broadcastSafe(displayTopic(event.getTenantId()), message,
                EVENT_TYPE_LAP_ADVANCED, event.getPhaseId(), event.getCorrelationId());
    }

    // =========================================================================
    // PhaseStatusChangedEvent — display only (E07S06 AC5)
    // =========================================================================

    /**
     * Forwards {@link PhaseStatusChangedEvent} to tenant-scoped display clients after commit
     * (E07S06 AC5).
     *
     * <p>Display clients receiving this event re-fetch the full phase overview and re-render
     * the Gesamtübersicht for the new phase.
     *
     * @param event the phase-status-changed event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhaseStatusChanged(PhaseStatusChangedEvent event) {
        EventMessage message = new EventMessage(
                EVENT_TYPE_PHASE_STATUS_CHANGED,
                event.getPhaseId(),
                Instant.now());

        log.debug("[tm-ws] Broadcasting {} for phaseId={} {} -> {}",
                EVENT_TYPE_PHASE_STATUS_CHANGED, event.getPhaseId(),
                event.getPreviousStatus(), event.getNewStatus());

        broadcastSafe(displayTopic(event.getTenantId()), message,
                EVENT_TYPE_PHASE_STATUS_CHANGED, event.getPhaseId(), null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Returns the tenant-scoped display WebSocket topic for the given tenant (AC11).
     *
     * @param tenantId the tenant UUID
     * @return e.g. {@code /topic/display/abc-123-def.../events}
     */
    static String displayTopic(UUID tenantId) {
        return String.format(DISPLAY_EVENTS_TOPIC_PATTERN, tenantId);
    }

    /**
     * Broadcasts a message to the given topic, swallowing any {@link MessagingException}.
     * Broadcast failures must NOT propagate (AC8).
     */
    private void broadcastSafe(String topic, EventMessage message,
                               String eventType, Object entityId, Object correlationId) {
        try {
            messagingTemplate.convertAndSend(topic, message);
        } catch (MessagingException e) {
            log.warn("[tm-ws] Failed to broadcast {} to {} entityId={} correlationId={}: {}",
                    eventType, topic, entityId, correlationId, e.getMessage());
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
