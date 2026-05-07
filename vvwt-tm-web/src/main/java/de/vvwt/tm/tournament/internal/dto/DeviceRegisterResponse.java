package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.tournament.Device;
import java.util.UUID;

/**
 * Response DTO for a successful device registration (E21S06 AC-TDD-DTOs, E49S01 AC2).
 *
 * <p>{@code pin} is nullable for DISPLAY devices (no PIN required). {@code deviceName} is non-null
 * for SCORING_TABLET (assigned at registration per E49S01 AC1); null for DISPLAY until configured.
 *
 * @see Device
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 * @see <a href="E49S01">E49S01 — AC2: register response includes deviceName</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceRegisterResponse(UUID id, String deviceToken, String pin, String deviceName) {

    /**
     * Factory method — builds a response from a persisted {@link Device}.
     *
     * @param device the registered device
     * @return the response DTO
     */
    public static DeviceRegisterResponse from(Device device) {
        return new DeviceRegisterResponse(
                device.getId(), device.getDeviceToken(), device.getPin(), device.getDeviceName());
    }
}
