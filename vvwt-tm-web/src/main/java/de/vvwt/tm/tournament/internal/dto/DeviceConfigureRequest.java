package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for configuring a DISPLAY device (E21S06 AC-TDD-DTOs).
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
public record DeviceConfigureRequest(
        @JsonProperty("deviceName") String deviceName,
        @JsonProperty("configuration") String configuration) {

    @JsonCreator
    public DeviceConfigureRequest {}
}
