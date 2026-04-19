package de.vvwt.tm.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Optional request body for POST /api/devices/register (E07S02 AC1).
 *
 * <p>If the body is absent or {@code deviceType} is absent, defaults to {@code SCORING_TABLET} for
 * backward compatibility with E06 tablets (AC6).
 *
 * <p>Valid values for {@code deviceType}:
 *
 * <ul>
 *   <li>{@code SCORING_TABLET} — creates a device with a generated PIN (default)
 *   <li>{@code DISPLAY} — creates a display device with no PIN (AC1)
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 */
public final class DeviceRegisterRequest {

    /** The requested device type. If null, defaults to SCORING_TABLET in the controller. */
    private final String deviceType;

    @JsonCreator
    public DeviceRegisterRequest(@JsonProperty("deviceType") String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceType() {
        return deviceType;
    }
}
