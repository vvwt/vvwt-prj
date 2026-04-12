package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.TeamService.BulkCreateResult;

import java.util.List;

/**
 * Response body for POST /api/tournaments/{tournamentId}/teams/bulk (AC5 — E05S05).
 *
 * <p>Contains per-item results. Each item either carries a created {@link TeamResponse}
 * (success) or an {@code errorMessage} (failure).
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story E05S05</a>
 */
public record TeamBulkCreateResponse(List<BulkItemResult> results) {

    /**
     * Per-item result in a bulk create response.
     *
     * @param team         the created team (null on failure)
     * @param errorMessage error detail (null on success)
     * @param success      true if the item was created successfully
     */
    public record BulkItemResult(TeamResponse team, String errorMessage, boolean success) {}

    /**
     * Maps a list of {@link BulkCreateResult} domain records to a {@link TeamBulkCreateResponse}.
     *
     * @param results the domain-layer results
     * @return the response DTO
     */
    public static TeamBulkCreateResponse from(List<BulkCreateResult> results) {
        List<BulkItemResult> items = results.stream()
                .map(r -> new BulkItemResult(
                        r.isSuccess() ? TeamResponse.from(r.team()) : null,
                        r.errorMessage(),
                        r.isSuccess()))
                .toList();
        return new TeamBulkCreateResponse(items);
    }
}
