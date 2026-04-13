package de.vvwt.tm.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.domain.Device;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response body for device lookup endpoints (AC4, AC5, AC6, E06S03).
 *
 * <p>Used for:
 * <ul>
 *   <li>GET /api/devices?pin={pin} — admin finds device by PIN (AC4)</li>
 *   <li>PUT /api/devices/{id}/assign — admin assigns device to field (AC5)</li>
 *   <li>PUT /api/devices/{id}/unassign — admin unassigns device (AC6)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceSummaryResponse(
        UUID id,
        String deviceType,
        String pin,
        String status,
        Integer assignedField,
        LocalDateTime registeredAt,
        LocalDateTime lastSeenAt
) {
    public static DeviceSummaryResponse from(Device device) {
        return new DeviceSummaryResponse(
                device.getId(),
                device.getDeviceType(),
                device.getPin(),
                device.getStatus(),
                device.getAssignedField(),
                device.getRegisteredAt(),
                device.getLastSeenAt()
        );
    }
}
