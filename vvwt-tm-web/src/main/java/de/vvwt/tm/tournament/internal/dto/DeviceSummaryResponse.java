package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.tournament.Device;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for device list and admin operations (E21S06 AC-TDD-DTOs).
 *
 * <p>Carries the full device summary: id, deviceToken, pin (nullable), deviceType, assignedField
 * (nullable), status, registeredAt (nullable), deviceName (nullable), configuration (nullable).
 *
 * @see Device
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceSummaryResponse(
        UUID id,
        String deviceToken,
        String pin,
        String deviceType,
        Integer assignedField,
        String status,
        LocalDateTime registeredAt,
        String deviceName,
        String configuration) {

    /**
     * Factory method — builds a summary response from a persisted {@link Device}.
     *
     * @param device the device
     * @return the summary response DTO
     */
    public static DeviceSummaryResponse from(Device device) {
        return new DeviceSummaryResponse(
                device.getId(),
                device.getDeviceToken(),
                device.getPin(),
                device.getDeviceType(),
                device.getAssignedField(),
                device.getStatus(),
                device.getRegisteredAt(),
                device.getDeviceName(),
                device.getConfiguration());
    }
}
