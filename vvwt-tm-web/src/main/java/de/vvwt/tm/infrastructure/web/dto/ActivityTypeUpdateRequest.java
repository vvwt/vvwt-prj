package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for PUT /api/tournaments/{tournamentId}/activity-types/{id} (E08S06, AC1).
 *
 * <p>Identical shape to {@link ActivityTypeCreateRequest}: all fields are re-validated
 * on update. Null {@code sortOrder} is normalised to 0 by the controller.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
public record ActivityTypeUpdateRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "assignmentRule is required")
        String assignmentRule,

        @Min(value = 1, message = "capacityPerRound must be at least 1 if provided")
        Integer capacityPerRound,

        Integer sortOrder
) {}
