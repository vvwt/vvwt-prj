package de.vvwt.tm.tournament;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for Device operations in the {@code tournament} bounded context.
 *
 * <p>Exposed in the public Modulith package ({@code de.vvwt.tm.tournament}) per DEC-35 — the
 * canonical implementation is {@link de.vvwt.tm.tournament.internal.DefaultDeviceService}.
 *
 * <p>Consumers inject this interface (not the implementation class) per DEC-35 § naming-canon and
 * DEC-36 § cross-package-test-typing-rule. Spring DI resolves to {@code
 * de.vvwt.tm.tournament.internal.DefaultDeviceService} (qualifier {@code "tmDeviceService"}).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultDeviceService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout (service interfaces in public
 *     package)</a>
 * @see <a href="E33S03">E33S03 — DeviceService interface extraction (DEC-35 retrofit)</a>
 */
public interface DeviceService {

    /**
     * Registers a new device for the current tenant.
     *
     * @param deviceType the device type ({@link Device#TYPE_SCORING_TABLET} or {@link
     *     Device#TYPE_DISPLAY})
     * @return the persisted device
     */
    Device register(String deviceType);

    /**
     * Returns the device with the given device token.
     *
     * @param deviceToken the device token
     * @return the device
     */
    Device getDeviceByToken(String deviceToken);

    /**
     * Returns all devices for the current tenant.
     *
     * @return list of devices; never null
     */
    List<Device> listDevices();

    /**
     * Configures a DISPLAY device with a human-readable name and JSON configuration.
     *
     * @param deviceId the device UUID
     * @param deviceName human-readable label for the display
     * @param configuration JSON configuration blob (may be null)
     * @return the updated device
     */
    Device configure(UUID deviceId, String deviceName, String configuration);

    /**
     * Assigns a location to the device (DEC-24 post-registration admin step).
     *
     * @param deviceId the device UUID
     * @param locationId the location UUID to assign
     * @return the updated device with locationId set
     */
    Device assignLocation(UUID deviceId, UUID locationId);

    /**
     * Removes the location assignment from a device (DEC-24).
     *
     * @param deviceId the device UUID
     * @return the updated device with locationId null
     */
    Device unassignLocation(UUID deviceId);

    /**
     * Returns the device matching the given PIN for the current tenant.
     *
     * @param pin the 4–6 digit PIN
     * @return the device
     */
    Device getDeviceByPin(String pin);

    /**
     * Assigns a device to a court field.
     *
     * @param deviceId the device UUID
     * @param fieldNumber the court field number (1-based)
     * @return the updated device
     */
    Device assignDevice(UUID deviceId, int fieldNumber);

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED.
     *
     * @param deviceId the device UUID
     * @return the updated device
     */
    Device unassignDevice(UUID deviceId);

    /**
     * Deletes the device with the given id.
     *
     * @param deviceId the device UUID
     */
    void deleteDevice(UUID deviceId);

    /** Deletes all devices for the current tenant. */
    void clearAllDevices();
}
