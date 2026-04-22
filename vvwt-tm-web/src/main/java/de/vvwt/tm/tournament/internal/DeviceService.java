package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.security.SecureRandom;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Domain service for Device CRUD operations — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors the logic of {@code de.vvwt.tm.domain.DeviceService} but wired to the new {@link
 * DeviceRepository} and new {@link Device} entity at the Modulith target package ({@code
 * de.vvwt.tm.tournament.*}).
 *
 * <h2>Business rules enforced</h2>
 *
 * <ul>
 *   <li>AC-DEVICELIMIT-ENFORCEMENT: total device count checked before registration; HTTP 409 if
 *       exceeded
 *   <li>SCORING_TABLET: 4–6 digit PIN generated at registration (unique within tenant, confusable
 *       digits excluded)
 *   <li>DISPLAY: no PIN (null) — registers via URL only
 *   <li>configure: DISPLAY-only; sets deviceName and configuration
 *   <li>assignLocation: sets locationId (DEC-24 post-registration admin step)
 *   <li>unassignLocation: sets locationId = null (DEC-24)
 *   <li>listDevices: delegates to repository
 *   <li>deleteDevice: delegates to repository
 * </ul>
 *
 * <h2>PIN generation algorithm</h2>
 *
 * <p>Mirrors legacy {@code de.vvwt.tm.domain.DeviceService} PIN logic: 4-digit PIN, confusable
 * digits (0, 1, 8) excluded → digits {2,3,4,5,6,7,9}. Retries up to 20 times if a PIN collision is
 * detected (race condition documented — not transactional).
 *
 * @see DeviceRepository
 * @see DeviceLimitConfig
 * @see DeviceLimitExceededException
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 170)</a>
 */
@Service("tmDeviceService")
public class DeviceService {

    /** Digits used for PIN generation — confusable digits 0, 1, 8 excluded (legacy parity). */
    private static final char[] PIN_DIGITS = {'2', '3', '4', '5', '6', '7', '9'};

    private static final int PIN_LENGTH = 4;
    private static final int MAX_PIN_RETRIES = 20;

    private final DeviceRepository deviceRepository;
    private final TenantContext tenantContext;
    private final DeviceLimitConfig limitConfig;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Maximum number of DISPLAY devices per tenant. Reads the legacy {@code
     * vvwt.devices.max-display-count} property. 0 means unlimited (disabled). Default 10.
     *
     * <p>Migrated from the deleted {@code de.vvwt.tm.config.DeviceLimitConfig} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving).
     */
    @Value("${vvwt.devices.max-display-count:10}")
    private int maxDisplayCount;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param deviceRepository device persistence
     * @param tenantContext current tenant context
     * @param limitConfig device limit configuration
     */
    public DeviceService(
            DeviceRepository deviceRepository,
            TenantContext tenantContext,
            DeviceLimitConfig limitConfig) {
        this.deviceRepository = deviceRepository;
        this.tenantContext = tenantContext;
        this.limitConfig = limitConfig;
    }

    // -------------------------------------------------------------------------
    // register
    // -------------------------------------------------------------------------

    /**
     * Registers a new device for the current tenant.
     *
     * <p>Enforces the device limit before registration. SCORING_TABLET devices receive a 4-digit
     * PIN; DISPLAY devices receive no PIN.
     *
     * @param deviceType the device type ({@link Device#TYPE_SCORING_TABLET} or {@link
     *     Device#TYPE_DISPLAY})
     * @return the persisted device
     * @throws DeviceLimitExceededException if the device count would exceed the configured limit
     */
    public Device register(String deviceType) {
        UUID tenantId = tenantContext.current();

        // Display-device-specific limit (429 Too Many Requests, legacy parity)
        String effectiveType = deviceType != null ? deviceType : Device.TYPE_SCORING_TABLET;

        // Validate device type — only SCORING_TABLET and DISPLAY are valid
        if (!Device.TYPE_SCORING_TABLET.equals(effectiveType)
                && !Device.TYPE_DISPLAY.equals(effectiveType)) {
            throw new IllegalArgumentException(
                    "Unknown deviceType: '"
                            + effectiveType
                            + "'. Valid values: "
                            + Device.TYPE_SCORING_TABLET
                            + ", "
                            + Device.TYPE_DISPLAY);
        }
        if (Device.TYPE_DISPLAY.equals(effectiveType) && maxDisplayCount > 0) {
            long displayCount = deviceRepository.countDisplayByTenant(tenantId);
            if (displayCount >= maxDisplayCount) {
                throw new DisplayDeviceLimitExceededException(maxDisplayCount, displayCount);
            }
        }

        // Total device limit (409 Conflict)
        long currentCount = deviceRepository.countByTenant(tenantId);
        int limit = limitConfig.getMaxDeviceCount();
        if (currentCount >= limit) {
            throw new DeviceLimitExceededException(limit, currentCount);
        }

        Device device = new Device();
        device.setId(UUID.randomUUID());
        device.setTenantId(tenantId);
        device.setDeviceToken(UUID.randomUUID().toString());
        device.setDeviceType(deviceType != null ? deviceType : Device.TYPE_SCORING_TABLET);
        device.setStatus(Device.STATUS_REGISTERED);
        device.setLocationId(null); // DEC-24: null at registration

        if (Device.TYPE_SCORING_TABLET.equals(device.getDeviceType())) {
            device.setPin(generateUniquePin());
        }
        // DISPLAY: pin stays null

        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // getDeviceByToken
    // -------------------------------------------------------------------------

    /**
     * Returns the device with the given device token.
     *
     * @param deviceToken the device token
     * @return the device
     * @throws NoSuchElementException if no device with this token exists
     */
    public Device getDeviceByToken(String deviceToken) {
        return deviceRepository
                .findByDeviceToken(deviceToken)
                .orElseThrow(
                        () ->
                                new NoSuchElementException(
                                        "Device not found for token: " + deviceToken));
    }

    // -------------------------------------------------------------------------
    // listDevices
    // -------------------------------------------------------------------------

    /**
     * Returns all devices for the current tenant.
     *
     * @return list of devices; never null
     */
    public List<Device> listDevices() {
        UUID tenantId = tenantContext.current();
        return deviceRepository.findAllByTenant(tenantId);
    }

    // -------------------------------------------------------------------------
    // configure
    // -------------------------------------------------------------------------

    /**
     * Configures a DISPLAY device with a human-readable name and JSON configuration.
     *
     * <p>Only DISPLAY devices may be configured via this method (SCORING_TABLET has no display
     * configuration).
     *
     * @param deviceId the device UUID
     * @param deviceName human-readable label for the display
     * @param configuration JSON configuration blob (may be null)
     * @return the updated device
     * @throws NoSuchElementException if the device does not exist for the current tenant
     * @throws IllegalArgumentException if the device is not a DISPLAY device
     */
    public Device configure(UUID deviceId, String deviceName, String configuration) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));
        if (!Device.TYPE_DISPLAY.equals(device.getDeviceType())) {
            throw new IllegalArgumentException(
                    "Only DISPLAY devices can be configured; device type is: "
                            + device.getDeviceType());
        }
        device.setDeviceName(deviceName);
        device.setConfiguration(configuration);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // assignLocation (DEC-24)
    // -------------------------------------------------------------------------

    /**
     * Assigns a location to the device (DEC-24 post-registration admin step).
     *
     * @param deviceId the device UUID
     * @param locationId the location UUID to assign
     * @return the updated device with locationId set
     * @throws NoSuchElementException if the device does not exist for the current tenant
     */
    public Device assignLocation(UUID deviceId, UUID locationId) {
        UUID tenantId = tenantContext.current();
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));
        // AC8 — cross-tenant guard: locationId must belong to the active tenant
        if (!deviceRepository.locationExistsForTenant(locationId, tenantId)) {
            throw new IllegalArgumentException(
                    "Location " + locationId + " does not exist for the active tenant");
        }
        device.setLocationId(locationId);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // unassignLocation (DEC-24)
    // -------------------------------------------------------------------------

    /**
     * Removes the location assignment from a device (DEC-24).
     *
     * <p>Sets {@code locationId = null}. Idempotent: no-op if already unassigned.
     *
     * @param deviceId the device UUID
     * @return the updated device with locationId null
     * @throws NoSuchElementException if the device does not exist for the current tenant
     */
    public Device unassignLocation(UUID deviceId) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));
        device.setLocationId(null);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // getDeviceByPin
    // -------------------------------------------------------------------------

    /**
     * Returns the device matching the given PIN for the current tenant.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param pin the 4–6 digit PIN
     * @return the device
     * @throws NoSuchElementException if no device with this PIN exists for the current tenant
     */
    public Device getDeviceByPin(String pin) {
        return deviceRepository
                .findByPin(pin)
                .orElseThrow(() -> new NoSuchElementException("No device found for PIN: " + pin));
    }

    // -------------------------------------------------------------------------
    // assignDevice
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a court field.
     *
     * <p>Validates that no other device is already assigned to the same field within the same
     * tenant (→ 409 Conflict if conflict). Sets {@code assignedField} and transitions status to
     * {@code ASSIGNED}.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param deviceId the device UUID
     * @param fieldNumber the court field number (1-based)
     * @return the updated device
     * @throws NoSuchElementException if the device is not found for the current tenant
     * @throws ConflictException if another device is already assigned to the same field
     */
    public Device assignDevice(UUID deviceId, int fieldNumber) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));

        // Conflict check: another device already assigned to this field (locationId may be null)
        Optional<Device> existing =
                deviceRepository.findByLocationAndField(device.getLocationId(), fieldNumber);
        if (existing.isPresent() && !existing.get().getId().equals(deviceId)) {
            throw new ConflictException(
                    "Field " + fieldNumber + " is already assigned to another device");
        }

        device.setAssignedField(fieldNumber);
        device.setStatus(Device.STATUS_ASSIGNED);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // unassignDevice
    // -------------------------------------------------------------------------

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED.
     *
     * <p>Idempotent: unassigning an already-unassigned device returns normally without error.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param deviceId the device UUID
     * @return the updated device
     * @throws NoSuchElementException if the device is not found for the current tenant
     */
    public Device unassignDevice(UUID deviceId) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));

        device.setAssignedField(null);
        device.setStatus(Device.STATUS_REGISTERED);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // deleteDevice
    // -------------------------------------------------------------------------

    /**
     * Deletes the device with the given id.
     *
     * @param deviceId the device UUID
     * @throws NoSuchElementException if the device does not exist for the current tenant
     */
    public void deleteDevice(UUID deviceId) {
        deviceRepository
                .findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Device not found: " + deviceId));
        deviceRepository.deleteById(deviceId);
    }

    // -------------------------------------------------------------------------
    // clearAllDevices
    // -------------------------------------------------------------------------

    /**
     * Deletes all devices for the current tenant.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving). Used by the admin "clear all" endpoint
     * (E06S05-AC7).
     */
    public void clearAllDevices() {
        deviceRepository.deleteAllByTenant();
    }

    // -------------------------------------------------------------------------
    // PIN generation helpers
    // -------------------------------------------------------------------------

    private String generateUniquePin() {
        for (int attempt = 0; attempt < MAX_PIN_RETRIES; attempt++) {
            String pin = generatePin();
            if (!deviceRepository.isPinTaken(pin)) {
                return pin;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique PIN after " + MAX_PIN_RETRIES + " attempts");
    }

    private String generatePin() {
        char[] pin = new char[PIN_LENGTH];
        for (int i = 0; i < PIN_LENGTH; i++) {
            pin[i] = PIN_DIGITS[secureRandom.nextInt(PIN_DIGITS.length)];
        }
        return new String(pin);
    }
}
