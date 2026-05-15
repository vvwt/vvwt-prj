package de.vvwt.tm.web.internal.dto;

import java.util.List;
import java.util.UUID;

/**
 * HTTP response DTO representing a single match summary in a phase's match list.
 *
 * <p>Used by {@code GET /api/phases/{phaseId}/matches}.
 *
 * <p>Web-tier-owned per DEC-40 Clause B — this record projects match state for the Admin SPA
 * correction navigation (PhaseList shows "Korrigieren" links per match row).
 *
 * @param matchId the UUID of the match
 * @param state the match state name (e.g., {@code "FINISHED_WINNER1"}, {@code "OPEN"})
 * @param lapNumber the lap number the match belongs to (nullable)
 * @param fieldNumber the field number the match is assigned to (nullable)
 * @param team1Name display name of the first team (derived from TeamAvatar → Team.description)
 * @param team2Name display name of the second team (derived from TeamAvatar → Team.description)
 * @param setScores ordered list of per-set scores (empty when no sets recorded yet)
 * @see de.vvwt.tm.web.MatchCorrectionController
 */
public record MatchSummaryResponse(
        UUID matchId,
        String state,
        Integer lapNumber,
        Integer fieldNumber,
        String team1Name,
        String team2Name,
        List<SetScoreDto> setScores) {

    /** Per-set score projection. */
    public record SetScoreDto(int setIndex, int team1Points, int team2Points) {}
}
