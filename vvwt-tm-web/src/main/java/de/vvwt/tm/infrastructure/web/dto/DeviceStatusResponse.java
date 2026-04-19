package de.vvwt.tm.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.domain.Device;

/**
 * Response body for GET /api/devices/status?token={deviceToken} (AC3, E06S03 + E07S02).
 *
 * <p>The tablet / display device polls this endpoint after registration. Extended in E07S02 to
 * include {@code configuration} and {@code deviceName} for display devices (AC3).
 *
 * <p>Scoring tablets receive the same {@code status} and {@code assignedField} fields as before.
 * The two new fields are omitted from JSON when null ({@link JsonInclude#NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStatusResponse(
        /** Current device status: REGISTERED, ASSIGNED, or DISCONNECTED. */
        String status,
        /**
         * Assigned court field number; null if the device has not been assigned yet. Omitted from
         * JSON when null ({@link JsonInclude#NON_NULL}).
         */
        Integer assignedField,
        /**
         * JSON configuration string (E07S02 AC3) — controls what a display device shows. Null for
         * scoring tablets and unconfigured display devices; omitted from JSON when null.
         */
        String configuration,
        /**
         * Human-readable device name (E07S02 AC3) — set by admin via configure endpoint. Null for
         * scoring tablets and unnamed display devices; omitted from JSON when null.
         */
        String deviceName) {
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(
                device.getStatus(),
                device.getAssignedField(),
                device.getConfiguration(),
                device.getDeviceName());
    }
}
