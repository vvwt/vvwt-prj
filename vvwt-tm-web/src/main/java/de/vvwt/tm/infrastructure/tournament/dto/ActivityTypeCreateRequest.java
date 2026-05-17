// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infrastructure.tournament.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/tournaments/{tournamentId}/activity-types} (E20S02, AC2).
 *
 * <p>Reconstructed TDD-first under Approach C (E20 methodology). Behaviour reference: {@code
 * archive/E08S06-pre-dec28} commit {@code 23f2ffa}.
 *
 * @see de.vvwt.tm.infrastructure.tournament.ActivityTypeController
 */
public record ActivityTypeCreateRequest(
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "assignmentRule is required") String assignmentRule,
        @Min(value = 1, message = "capacityPerRound must be at least 1 if provided")
                Integer capacityPerRound,
        Integer sortOrder) {}
