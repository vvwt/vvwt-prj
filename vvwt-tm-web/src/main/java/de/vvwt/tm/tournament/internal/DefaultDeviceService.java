// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException;
import de.vvwt.tm.tournament.exceptions.DevicePinLockedException;
import de.vvwt.tm.tournament.exceptions.DisplayDeviceLimitExceededException;
import de.vvwt.tm.tournament.exceptions.PinMismatchException;
import de.vvwt.tm.tournament.exceptions.PinMissingForTabletException;
import de.vvwt.tm.tournament.exceptions.RenameNotSupportedForDisplayException;
import de.vvwt.tm.tournament.exceptions.UnexpectedPinForDisplayException;
import java.security.SecureRandom;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link DeviceService} — Device CRUD operations for the {@code
 * tournament} bounded context (DEC-35 retrofit, E33S03).
 *
 * <p>Previously named {@code DeviceService}; renamed to {@code DefaultDeviceService} per DEC-35
 * naming canon ({@code Default{Foo}Service} for the canonical implementation of {@code
 * {Foo}Service}). Logic is behavior-preserving (Q-1b refactor under DEC-22 §refactor-clause).
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
 * @see <a href="DEC-35">DEC-35 — Default* naming canon for service implementations</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 170)</a>
 * @see <a href="E33S03">E33S03 — DEC-35 interface-extraction retrofit</a>
 * @see <a href="E49S01">E49S01 — PIN out-of-band + inline-row assignment + device name</a>
 */
@Service("tmDeviceService")
public class DefaultDeviceService implements DeviceService {

    /** Digits used for PIN generation — confusable digits 0, 1, 8 excluded (legacy parity). */
    private static final char[] PIN_DIGITS = {'2', '3', '4', '5', '6', '7', '9'};

    private static final int PIN_LENGTH = 4;
    private static final int MAX_PIN_RETRIES = 20;

    /**
     * Alphanumeric characters for device name suffix generation — uppercase A-Z plus digits 2-9
     * (confusable digits 0, 1, 8 excluded for readability).
     */
    private static final char[] NAME_CHARS = {
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
        'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
        '2', '3', '4', '5', '6', '7', '9'
    };

    private static final int NAME_SUFFIX_LENGTH = 4;
    private static final int MAX_NAME_RETRIES = 20;

    /** Maximum consecutive PIN failures before the device is locked (E49S01 AC6). */
    private static final int PIN_LOCK_THRESHOLD = 10;

    private final DeviceRepository deviceRepository;
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
     * @param limitConfig device limit configuration
     */
    public DefaultDeviceService(DeviceRepository deviceRepository, DeviceLimitConfig limitConfig) {
        this.deviceRepository = deviceRepository;
        this.limitConfig = limitConfig;
    }

    // -------------------------------------------------------------------------
    // register
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Enforces the device limit before registration. SCORING_TABLET devices receive a 4-digit
     * PIN; DISPLAY devices receive no PIN.
     *
     * @throws DeviceLimitExceededException if the device count would exceed the configured limit
     */
    @Override
    public Device register(String deviceType) {
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
            long displayCount = deviceRepository.countDisplayByTenant();
            if (displayCount >= maxDisplayCount) {
                throw new DisplayDeviceLimitExceededException(maxDisplayCount, displayCount);
            }
        }

        // Total device limit (409 Conflict)
        long currentCount = deviceRepository.countByTenant();
        int limit = limitConfig.getMaxDeviceCount();
        if (currentCount >= limit) {
            throw new DeviceLimitExceededException(limit, currentCount);
        }

        Device device = new Device();
        device.setId(UUID.randomUUID());
        device.setDeviceToken(UUID.randomUUID().toString());
        device.setDeviceType(deviceType != null ? deviceType : Device.TYPE_SCORING_TABLET);
        device.setStatus(Device.STATUS_REGISTERED);
        device.setLocationId(null); // DEC-24: null at registration

        if (Device.TYPE_SCORING_TABLET.equals(device.getDeviceType())) {
            device.setPin(generateUniquePin());
            device.setDeviceName(generateUniqueDeviceName());
        }
        // DISPLAY: pin stays null; name set via configure()

        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // getDeviceByToken
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
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

    /** {@inheritDoc} */
    @Override
    public List<Device> listDevices() {
        return deviceRepository.findAllByTenant();
    }

    // -------------------------------------------------------------------------
    // configure
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Only DISPLAY devices may be configured via this method (SCORING_TABLET has no display
     * configuration).
     *
     * @throws NoSuchElementException if the device does not exist for the current tenant
     * @throws IllegalArgumentException if the device is not a DISPLAY device
     */
    @Override
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
     * {@inheritDoc}
     *
     * @throws NoSuchElementException if the device does not exist for the current tenant
     */
    @Override
    public Device assignLocation(UUID deviceId, UUID locationId) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));
        // AC8 — validate locationId exists in the current tenant database
        if (!deviceRepository.locationExistsForTenant(locationId)) {
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
     * {@inheritDoc}
     *
     * <p>Sets {@code locationId = null}. Idempotent: no-op if already unassigned.
     *
     * @throws NoSuchElementException if the device does not exist for the current tenant
     */
    @Override
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
    // assignDevice (E49S01 — PIN out-of-band)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>For SCORING_TABLET: verifies pin is non-null, device is not PIN-locked, and pin matches.
     * On mismatch the fail-counter is incremented; on success it is reset to 0.
     *
     * <p>For DISPLAY: pin must be null.
     *
     * <p>Validates that no other device is already assigned to the same field within the same
     * tenant (→ 409 Conflict if conflict). Sets {@code assignedField} and transitions status to
     * {@code ASSIGNED}.
     *
     * @throws NoSuchElementException if the device is not found for the current tenant
     * @throws PinMissingForTabletException if SCORING_TABLET and pin is null
     * @throws UnexpectedPinForDisplayException if DISPLAY and pin is non-null
     * @throws DevicePinLockedException if the device's fail-counter has reached N=10
     * @throws PinMismatchException if pin does not match the stored PIN
     * @throws ConflictException if another device is already assigned to the same field
     */
    @Override
    public Device assignDevice(UUID deviceId, int fieldNumber, String pin) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));

        boolean isTablet = Device.TYPE_SCORING_TABLET.equals(device.getDeviceType());
        boolean isDisplay = Device.TYPE_DISPLAY.equals(device.getDeviceType());

        if (isTablet) {
            if (pin == null || pin.isBlank()) {
                throw new PinMissingForTabletException();
            }
            int failCount = deviceRepository.getPinFailCount(deviceId);
            if (failCount >= PIN_LOCK_THRESHOLD) {
                throw new DevicePinLockedException(deviceId);
            }
            if (!pin.equals(device.getPin())) {
                deviceRepository.incrementPinFailCount(deviceId);
                throw new PinMismatchException();
            }
            // PIN correct — reset fail-counter
            deviceRepository.resetPinFailCount(deviceId);
        } else if (isDisplay) {
            if (pin != null && !pin.isBlank()) {
                throw new UnexpectedPinForDisplayException();
            }
        }

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
    // resetPinLockCounter (E49S01 AC6)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * @throws NoSuchElementException if the device is not found for the current tenant
     */
    @Override
    public void resetPinLockCounter(UUID deviceId) {
        deviceRepository
                .findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Device not found: " + deviceId));
        deviceRepository.resetPinFailCount(deviceId);
    }

    // -------------------------------------------------------------------------
    // renameDevice (E49S01 AC11)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * @throws NoSuchElementException if the device is not found for the current tenant
     * @throws RenameNotSupportedForDisplayException if the device is a DISPLAY device
     */
    @Override
    public Device renameDevice(UUID deviceId, String newName) {
        Device device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(
                                () -> new NoSuchElementException("Device not found: " + deviceId));
        if (Device.TYPE_DISPLAY.equals(device.getDeviceType())) {
            throw new RenameNotSupportedForDisplayException();
        }
        device.setDeviceName(newName);
        return deviceRepository.save(device);
    }

    // -------------------------------------------------------------------------
    // generateUniqueDeviceName (E49S01 AC1)
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if a unique name cannot be generated after 20 attempts
     */
    @Override
    public String generateUniqueDeviceName() {
        for (int attempt = 0; attempt < MAX_NAME_RETRIES; attempt++) {
            String name = generateDeviceName();
            if (!deviceRepository.isNameTaken(name)) {
                return name;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique device name after " + MAX_NAME_RETRIES + " attempts");
    }

    // -------------------------------------------------------------------------
    // unassignDevice
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent: unassigning an already-unassigned device returns normally without error.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @throws NoSuchElementException if the device is not found for the current tenant
     */
    @Override
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

    /** {@inheritDoc} */
    @Override
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
     * {@inheritDoc}
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.domain.DeviceService} during E21S13
     * cutover (DEC-22 refactor phase — behavior-preserving). Used by the admin "clear all" endpoint
     * (E06S05-AC7).
     */
    @Override
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

    private String generateDeviceName() {
        char[] suffix = new char[NAME_SUFFIX_LENGTH];
        for (int i = 0; i < NAME_SUFFIX_LENGTH; i++) {
            suffix[i] = NAME_CHARS[secureRandom.nextInt(NAME_CHARS.length)];
        }
        return "Tablet-" + new String(suffix);
    }
}
