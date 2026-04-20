package de.vvwt.tm.tournament.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request body for POST /api/tm/tournaments/{tournamentId}/teams/bulk (E21S04, AC-TDD-TeamDTOs).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.dto.TeamBulkCreateRequest} but lives in the
 * Modulith target package {@code de.vvwt.tm.tournament.internal.dto}.
 *
 * <p>Inventory line 441 ({@code TeamBulkCreateRequest}).
 *
 * @see de.vvwt.tm.tournament.internal.TeamService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 441)</a>
 */
public record TeamBulkCreateRequest(

        /** The list of teams to create. Must not be null. */
        @NotNull(message = "teams list is required") @Valid List<TeamCreateRequest> teams) {}
