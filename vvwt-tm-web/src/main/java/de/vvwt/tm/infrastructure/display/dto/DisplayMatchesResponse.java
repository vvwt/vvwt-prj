package de.vvwt.tm.infrastructure.display.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/display/overview/matches} (E07S04, AC2).
 *
 * <p>Returns all matches for the given (or current) lap in the active phase, including team names,
 * set results, match status, and referee team name.
 *
 * @see de.vvwt.tm.infrastructure.display.DisplayOverviewService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 */
public record DisplayMatchesResponse(UUID phaseId, int lap, List<MatchEntry> matches) {

    /**
     * A single match within the lap.
     *
     * @param matchId the match UUID
     * @param fieldNumber the court field number (null if not yet slot-optimized)
     * @param teamAName name of team A (avatar 1)
     * @param teamBName name of team B (avatar 2)
     * @param setResults list of set scores in play order
     * @param matchStatus display status: {@code PENDING}, {@code IN_PROGRESS}, or {@code COMPLETED}
     * @param refereeTeamName name of the referee team, or {@code null} if none assigned
     */
    public record MatchEntry(
            UUID matchId,
            Integer fieldNumber,
            String teamAName,
            String teamBName,
            List<SetResultEntry> setResults,
            String matchStatus,
            String refereeTeamName) {}

    /**
     * The score of a single set.
     *
     * @param setIndex 0-based index of the set within the match
     * @param scoreA points scored by team A in this set
     * @param scoreB points scored by team B in this set
     */
    public record SetResultEntry(int setIndex, int scoreA, int scoreB) {}
}
