package de.vvwt.tm.tournament.events;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring {@link ApplicationEvent} published after a new device is registered in the tournament
 * context (E21S09, AC-TDD-DeviceRegisteredEvent, AC-PKG-DeviceRegisteredEvent).
 *
 * <p>This is the new public-API event at {@code de.vvwt.tm.tournament.events.*} per DEC-21 (D-8
 * package discipline). It replaces the legacy {@code de.vvwt.tm.domain.event.DeviceRegisteredEvent}
 * at atomic cutover time. During the parallel-development phase, both coexist.
 *
 * <p>Published via {@link org.springframework.context.ApplicationEventPublisher}. Consumers in
 * other bounded contexts (e.g., {@code DomainEventBridge} in E21S10) use {@code @EventListener} or
 * {@code @TransactionalEventListener} to receive this event.
 *
 * <h2>Payload</h2>
 *
 * <p>Payload fields are 1:1 with the legacy event type to allow consumers to migrate without logic
 * changes.
 *
 * <h2>MUST NOT carry sensitive payloads</h2>
 *
 * <p>This event class MUST NOT embed credentials, session tokens, or raw SQL. The only payload is
 * the {@code deviceId} UUID (AC-SEC-EXCEPTION-NO-LEAK by analogy to events).
 *
 * @see de.vvwt.tm.domain.event.DeviceRegisteredEvent legacy counterpart (untouched until cutover)
 */
public class DeviceRegisteredEvent extends ApplicationEvent {

    /** UUID of the newly registered device. */
    private final UUID deviceId;

    /**
     * Constructs a {@code DeviceRegisteredEvent}.
     *
     * @param source the object on which the event initially occurred (must not be {@code null} —
     *     enforced by {@link ApplicationEvent#ApplicationEvent(Object)})
     * @param deviceId the UUID of the newly registered device (must not be {@code null})
     */
    public DeviceRegisteredEvent(Object source, UUID deviceId) {
        super(source);
        if (deviceId == null) {
            throw new NullPointerException("deviceId must not be null");
        }
        this.deviceId = deviceId;
    }

    /**
     * Returns the UUID of the newly registered device.
     *
     * @return the device UUID
     */
    public UUID getDeviceId() {
        return deviceId;
    }

    @Override
    public String toString() {
        return "DeviceRegisteredEvent{deviceId=" + deviceId + '}';
    }
}
