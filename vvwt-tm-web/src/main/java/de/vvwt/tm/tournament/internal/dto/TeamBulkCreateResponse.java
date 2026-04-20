package de.vvwt.tm.tournament.internal.dto;

import de.vvwt.tm.tournament.internal.TeamService.BulkCreateResult;
import java.util.List;

/**
 * Response body for POST /api/tm/tournaments/{tournamentId}/teams/bulk (E21S04, AC-TDD-TeamDTOs).
 *
 * <p>Contains per-item results. Each item either carries a created {@link TeamResponse} (success)
 * or an {@code errorMessage} (failure).
 *
 * <p>Inventory line 442 ({@code TeamBulkCreateResponse}).
 *
 * @see de.vvwt.tm.tournament.internal.TeamService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 442)</a>
 */
public record TeamBulkCreateResponse(List<BulkItemResult> results) {

    /**
     * Per-item result in a bulk create response.
     *
     * @param team the created team (null on failure)
     * @param errorMessage error detail (null on success)
     * @param success true if the item was created successfully
     */
    public record BulkItemResult(TeamResponse team, String errorMessage, boolean success) {}

    /**
     * Maps a list of {@link BulkCreateResult} domain records to a {@link TeamBulkCreateResponse}.
     *
     * @param results the domain-layer results
     * @return the response DTO
     */
    public static TeamBulkCreateResponse from(List<BulkCreateResult> results) {
        List<BulkItemResult> items =
                results.stream()
                        .map(
                                r ->
                                        new BulkItemResult(
                                                r.isSuccess() ? TeamResponse.from(r.team()) : null,
                                                r.errorMessage(),
                                                r.isSuccess()))
                        .toList();
        return new TeamBulkCreateResponse(items);
    }
}
