package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.tournament.Device;
import java.time.LocalDateTime;

/**
 * Response DTO for the device status endpoint (E21S06 AC-TDD-DTOs).
 *
 * <p>{@code assignedField} and {@code registeredAt} are nullable.
 *
 * @see Device
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStatusResponse(
        String status, Integer assignedField, LocalDateTime registeredAt) {

    /**
     * Factory method — builds a response from a persisted {@link Device}.
     *
     * @param device the device
     * @return the status response DTO
     */
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(
                device.getStatus(), device.getAssignedField(), device.getRegisteredAt());
    }
}
