package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for POST /api/tournaments/{tournamentId}/teams/bulk (AC5 — E05S05).
 *
 * <p>Wraps a list of {@link TeamCreateRequest} items for bulk team creation.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story E05S05</a>
 */
public record TeamBulkCreateRequest(

        /** The list of teams to create. Must not be null or empty. */
        @NotNull(message = "teams list is required")
        @Valid
        List<TeamCreateRequest> teams
) {}
