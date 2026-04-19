package de.vvwt.tm.infrastructure.tournament.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PUT /api/tournaments/{tournamentId}/activity-types/{id}} (E20S02, AC2).
 *
 * <p>Reconstructed TDD-first under Approach C (E20 methodology). Same field shape as {@link
 * ActivityTypeCreateRequest}: all fields re-validated on update.
 *
 * @see de.vvwt.tm.infrastructure.tournament.ActivityTypeController
 */
public record ActivityTypeUpdateRequest(
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "assignmentRule is required") String assignmentRule,
        @Min(value = 1, message = "capacityPerRound must be at least 1 if provided")
                Integer capacityPerRound,
        Integer sortOrder) {}
