// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for registering a new device (E21S06 AC-TDD-DTOs).
 *
 * <p>{@code deviceType} is nullable — defaults to {@code SCORING_TABLET} if absent.
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
public record DeviceRegisterRequest(@JsonProperty("deviceType") String deviceType) {

    @JsonCreator
    public DeviceRegisterRequest {}
}
