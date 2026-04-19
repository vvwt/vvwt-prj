package de.vvwt.tm.domain.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published by {@link de.vvwt.tm.domain.DeviceService} after a new device
 * is persisted (E06S05, AC6).
 *
 * <p>The event is published via {@link org.springframework.context.ApplicationEventPublisher}
 * inside the {@code registerDevice()} method (non-transactional path — device registration is not
 * wrapped in a transaction at the service layer in V1). The listener in {@link
 * de.vvwt.tm.infrastructure.web.DomainEventBridge} uses {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} — because device registration is not transactional, the event fires immediately in
 * the same thread.
 *
 * <p>The admin SPA subscribes to {@code /topic/events} and reacts to {@code eventType ==
 * "DEVICE_REGISTERED"} by refreshing the device list.
 *
 * @see de.vvwt.tm.domain.DeviceService
 * @see de.vvwt.tm.infrastructure.web.DomainEventBridge
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S05.story.md">Story
 *     E06S05</a>
 */
public class DeviceRegisteredEvent extends ApplicationEvent {

    /** UUID of the newly registered device. Used by the SPA to update its list. */
    private final UUID deviceId;

    /**
     * Constructs a {@code DeviceRegisteredEvent}.
     *
     * @param source the object on which the event initially occurred (the {@link
     *     de.vvwt.tm.domain.DeviceService})
     * @param deviceId the UUID of the newly registered device
     */
    public DeviceRegisteredEvent(Object source, UUID deviceId) {
        super(source);
        if (deviceId == null) throw new NullPointerException("deviceId must not be null");
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
