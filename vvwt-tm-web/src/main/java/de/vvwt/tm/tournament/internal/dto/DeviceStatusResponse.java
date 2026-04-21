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
        String status,
        Integer assignedField,
        LocalDateTime registeredAt,
        String deviceName,
        String configuration) {

    /**
     * Backward-compat 3-arg constructor for test code that predates the {@code deviceName} and
     * {@code configuration} fields (E21S13 cutover — DEC-22 refactor phase).
     *
     * @param status device status
     * @param assignedField assigned field number (nullable)
     * @param registeredAt registration timestamp (nullable)
     */
    public DeviceStatusResponse(String status, Integer assignedField, LocalDateTime registeredAt) {
        this(status, assignedField, registeredAt, null, null);
    }

    /**
     * Factory method — builds a response from a persisted {@link Device}.
     *
     * @param device the device
     * @return the status response DTO
     */
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(
                device.getStatus(),
                device.getAssignedField(),
                device.getRegisteredAt(),
                device.getDeviceName(),
                device.getConfiguration());
    }
}
