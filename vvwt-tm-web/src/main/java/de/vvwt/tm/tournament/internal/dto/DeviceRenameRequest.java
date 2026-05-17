// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for renaming a SCORING_TABLET device (E49S01 AC11).
 *
 * @see <a href="E49S01">E49S01 — AC11: rename SCORING_TABLET</a>
 */
public record DeviceRenameRequest(
        @JsonProperty("newName")
                @NotBlank(message = "newName must not be blank")
                @Size(max = 100, message = "newName must not exceed 100 characters")
                String newName) {

    @JsonCreator
    public DeviceRenameRequest {}
}
