package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MatchCorrectionService;

import java.util.List;
import java.util.UUID;

/**
 * REST response DTO for match detail (AC1, AC2, AC3 — E05S11).
 *
 * <p>Returned by {@code GET /api/matches/{matchId}},
 * {@code PUT /api/matches/{matchId}/sets/{setIndex}}, and
 * {@code POST /api/matches/{matchId}/sets}.
 *
 * @param matchId            the match UUID
 * @param phaseId            the phase this match belongs to
 * @param tournamentId       the tournament this match belongs to
 * @param team1Description   human-readable description for team 1
 * @param team2Description   human-readable description for team 2
 * @param refereeDescription human-readable description for the referee team
 * @param matchFormat        match format name (e.g., {@code "BEST_OF_3"})
 * @param matchState         match state name (e.g., {@code "ENABLED"}, {@code "FINISHED_WINNER1"})
 * @param setLimit           maximum number of sets for this match
 * @param setResults         set results in ascending setIndex order
 */
public record MatchDetailResponse(
        UUID matchId,
        UUID phaseId,
        UUID tournamentId,
        String team1Description,
        String team2Description,
        String refereeDescription,
        String matchFormat,
        String matchState,
        int setLimit,
        List<SetResultDetailEntry> setResults
) {

    /**
     * Converts a domain {@link MatchCorrectionService.MatchDetail} to a response DTO.
     *
     * @param detail the domain record (must not be {@code null})
     * @return the response DTO
     */
    public static MatchDetailResponse from(MatchCorrectionService.MatchDetail detail) {
        List<SetResultDetailEntry> entries = detail.setResults().stream()
                .map(SetResultDetailEntry::from)
                .toList();

        return new MatchDetailResponse(
                detail.matchId(),
                detail.phaseId(),
                detail.tournamentId(),
                detail.team1Description(),
                detail.team2Description(),
                detail.refereeDescription(),
                detail.matchFormat(),
                detail.matchState(),
                detail.setLimit(),
                entries
        );
    }
}
