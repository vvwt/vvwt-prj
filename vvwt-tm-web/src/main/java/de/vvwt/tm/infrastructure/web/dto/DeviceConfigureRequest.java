package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for PUT /api/devices/{id}/configure (E07S02 AC4).
 *
 * <p>Sets the human-readable device name and optional JSON configuration for a DISPLAY device.
 * Returns 400 if the device is not of type DISPLAY.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story E07S02</a>
 */
public record DeviceConfigureRequest(
        /**
         * Human-readable device name (e.g., "Halle Eingang"). Required, non-blank (AC4).
         */
        @NotBlank(message = "deviceName must not be blank")
        String deviceName,

        /**
         * JSON configuration string controlling the device's display behaviour (AC4).
         * Example: {@code {"display_schema": "OVERVIEW"}}.
         * Null clears the current configuration (unassigns the display schema).
         */
        String configuration
) {
}
