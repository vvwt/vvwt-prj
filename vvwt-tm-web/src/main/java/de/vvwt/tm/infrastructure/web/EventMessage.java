package de.vvwt.tm.infrastructure.web;

import java.time.Instant;
import java.util.UUID;

/**
 * WebSocket notification message broadcast to connected clients when a domain event occurs (AC5,
 * AC10, E05S03).
 *
 * <h2>Security (AC10)</h2>
 *
 * <p>This DTO intentionally contains minimal information:
 *
 * <ul>
 *   <li>{@code eventType} — a string identifier for the event category (e.g., {@code
 *       "MATCH_RESULT_CHANGED"}). The SPA uses this to decide which REST endpoint to call for fresh
 *       data.
 *   <li>{@code entityId} — the UUID of the affected entity (e.g., the match UUID). This is a lookup
 *       key for the REST API, not an internal DB row ID. The default-tenant LAN model uses UUIDs as
 *       public identifiers, so exposure in the WebSocket message is consistent with REST API
 *       behavior.
 *   <li>{@code timestamp} — the instant the event was emitted by the domain service. Allows the SPA
 *       to detect stale messages if it reconnects late.
 * </ul>
 *
 * <p>Passwords, internal H2 row IDs (integer sequences), and any data not already accessible via
 * the authenticated REST API are explicitly excluded (AC10).
 *
 * <h2>Extensibility (AC5 notes)</h2>
 *
 * <p>Later stories add new event types by setting a different {@code eventType} string. The SPA
 * dispatches on the {@code eventType} to fetch the appropriate resource. No structural change to
 * {@link EventMessage} is required.
 *
 * @see DomainEventBridge
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
public final class EventMessage {

    /**
     * String identifier for the event category.
     *
     * <p>Well-known values:
     *
     * <ul>
     *   <li>{@code "MATCH_RESULT_CHANGED"} — a match result was registered or corrected (from
     *       {@link de.vvwt.tm.domain.event.MatchResultChangedEvent})
     * </ul>
     */
    private final String eventType;

    /**
     * UUID of the affected entity.
     *
     * <p>For {@code MATCH_RESULT_CHANGED}: the match UUID. The SPA uses this to call {@code GET
     * /api/matches/{entityId}} for the updated match state.
     */
    private final UUID entityId;

    /**
     * ISO-8601 instant when the domain event was emitted.
     *
     * <p>Allows the SPA to handle out-of-order delivery or detect stale messages after a reconnect.
     */
    private final Instant timestamp;

    /**
     * Constructs an {@link EventMessage}.
     *
     * @param eventType the event category identifier (must not be null)
     * @param entityId the affected entity UUID (must not be null)
     * @param timestamp the event emission time (must not be null)
     */
    public EventMessage(String eventType, UUID entityId, Instant timestamp) {
        if (eventType == null) throw new NullPointerException("eventType must not be null");
        if (entityId == null) throw new NullPointerException("entityId must not be null");
        if (timestamp == null) throw new NullPointerException("timestamp must not be null");
        this.eventType = eventType;
        this.entityId = entityId;
        this.timestamp = timestamp;
    }

    /**
     * @return the event category identifier
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * @return the affected entity UUID
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * @return the event emission timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
}
