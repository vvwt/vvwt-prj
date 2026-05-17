// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * @see <a href="E49S01">E49S01 — PIN out-of-band + inline-row assignment + device name</a>
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
     * Assigns a device to a court field.
     *
     * <p>For SCORING_TABLET devices, {@code pin} must be provided and match the device's stored PIN
     * (E49S01 AC5). For DISPLAY devices, {@code pin} must be {@code null} (E49S01 AC5).
     *
     * @param deviceId the device UUID
     * @param fieldNumber the court field number (1-based)
     * @param pin the PIN supplied by the admin (nullable; required for SCORING_TABLET)
     * @return the updated device
     * @throws de.vvwt.tm.tournament.exceptions.PinMissingForTabletException if SCORING_TABLET and
     *     pin is null
     * @throws de.vvwt.tm.tournament.exceptions.UnexpectedPinForDisplayException if DISPLAY and pin
     *     is non-null
     * @throws de.vvwt.tm.tournament.exceptions.DevicePinLockedException if the device is PIN-locked
     * @throws de.vvwt.tm.tournament.exceptions.PinMismatchException if pin is wrong
     * @see <a href="E49S01">E49S01 — AC5/AC6: PIN check on assign</a>
     */
    Device assignDevice(UUID deviceId, int fieldNumber, String pin);

    /**
     * Generates a unique device name for the current tenant.
     *
     * <p>Format: {@code Tablet-XXXX} where XXXX is a 4-char alphanumeric suffix. Retries up to 20
     * times on name collision.
     *
     * @return a unique device name not currently used by any device in the tenant
     * @see <a href="E49S01">E49S01 — AC1: unique device name generation</a>
     */
    String generateUniqueDeviceName();

    /**
     * Resets the PIN fail-counter for the given device to 0.
     *
     * <p>Called by the admin via {@code POST /api/devices/{id}/pin-lock/reset}.
     *
     * @param deviceId the device UUID
     * @throws java.util.NoSuchElementException if the device is not found for the current tenant
     * @see <a href="E49S01">E49S01 — AC6: manual counter reset</a>
     */
    void resetPinLockCounter(UUID deviceId);

    /**
     * Renames a SCORING_TABLET device.
     *
     * <p>Only SCORING_TABLET devices may be renamed via this method; use {@code configure} for
     * DISPLAY devices.
     *
     * @param deviceId the device UUID
     * @param newName the new device name
     * @return the updated device
     * @throws java.util.NoSuchElementException if the device is not found for the current tenant
     * @throws de.vvwt.tm.tournament.exceptions.RenameNotSupportedForDisplayException if device is
     *     DISPLAY
     * @see <a href="E49S01">E49S01 — AC11: rename SCORING_TABLET</a>
     */
    Device renameDevice(UUID deviceId, String newName);

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
