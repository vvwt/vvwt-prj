// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for configuring a DISPLAY device (E21S06 AC-TDD-DTOs).
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
public record DeviceConfigureRequest(
        @JsonProperty("deviceName") @NotBlank(message = "deviceName must not be blank")
                String deviceName,
        @JsonProperty("configuration") String configuration) {

    @JsonCreator
    public DeviceConfigureRequest {}
}
