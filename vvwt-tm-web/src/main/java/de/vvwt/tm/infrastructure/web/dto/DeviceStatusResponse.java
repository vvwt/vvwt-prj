package de.vvwt.tm.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.domain.Device;

/**
 * Response body for GET /api/devices/status?token={deviceToken} (AC3, E06S03).
 *
 * <p>The tablet polls this endpoint after registration to check whether it has been
 * assigned to a court field by the organizer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStatusResponse(
        /** Current device status: REGISTERED, ASSIGNED, or DISCONNECTED. */
        String status,
        /**
         * Assigned court field number; null if the device has not been assigned yet.
         * Omitted from JSON when null ({@link JsonInclude#NON_NULL}).
         */
        Integer assignedField
) {
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(device.getStatus(), device.getAssignedField());
    }
}
