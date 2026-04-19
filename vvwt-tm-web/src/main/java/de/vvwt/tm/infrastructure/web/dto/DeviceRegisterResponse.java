package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.Device;

/**
 * Response body for POST /api/devices/register (AC2, E06S03).
 *
 * <p>Returns the device token and PIN after a successful registration. The tablet stores the device
 * token for subsequent polling; the PIN is displayed to the operator for assignment.
 */
public record DeviceRegisterResponse(
        /** Opaque cryptographically random token — used by the tablet to identify itself. */
        String deviceToken,
        /** Short numeric PIN displayed on the tablet screen for the organizer to use. */
        String pin) {
    public static DeviceRegisterResponse from(Device device) {
        return new DeviceRegisterResponse(device.getDeviceToken(), device.getPin());
    }
}
