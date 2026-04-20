package de.vvwt.tm.tournament.internal.web;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * WebSocket notification message broadcast to connected clients when a domain event occurs (E21S10,
 * AC-TDD-EventMessage, AC-PKG-EventMessage, inventory row 449).
 *
 * <p>Tournament-internal DTO: consumed only by {@link DomainEventBridge} as the broadcast payload.
 * The JSON wire format is the consumer contract (subscribed WebSocket clients read the JSON), not
 * the Java type itself. Placed at {@code de.vvwt.tm.tournament.internal.web} per DEC-21 D-8.
 *
 * <h2>Minimal payload (security)</h2>
 *
 * <ul>
 *   <li>{@code eventType} — string identifier (e.g., {@code "MATCH_RESULT_CHANGED"})
 *   <li>{@code entityId} — UUID of the affected entity (public lookup key, consistent with REST API
 *       behavior)
 *   <li>{@code timestamp} — emission instant; allows stale-message detection on reconnect
 * </ul>
 *
 * <p>Passwords, internal row IDs, and data not accessible via the REST API are excluded.
 *
 * @see DomainEventBridge
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal vs public package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S10">E21S10 — inventory row 449</a>
 */
public final class EventMessage {

    private final String eventType;
    private final UUID entityId;
    private final Instant timestamp;

    /**
     * Constructs an {@link EventMessage}.
     *
     * @param eventType event category identifier (must not be null)
     * @param entityId affected entity UUID (must not be null)
     * @param timestamp event emission instant (must not be null)
     * @throws NullPointerException if any argument is null
     */
    public EventMessage(String eventType, UUID entityId, Instant timestamp) {
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /**
     * @return event category identifier
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * @return affected entity UUID
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * @return event emission timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
}
