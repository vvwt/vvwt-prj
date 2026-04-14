package de.vvwt.tm.infrastructure.display.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/display/overview/groups} (E07S04, AC3).
 *
 * <p>Returns group standings for the current active phase. Each group contains a list of
 * team rankings ordered by position (D-33 sort order: points DESC, set quotient DESC,
 * ball quotient DESC, withoutAssessment last).
 *
 * @see de.vvwt.tm.infrastructure.display.DisplayOverviewService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story E07S04</a>
 */
public record DisplayGroupStandingsResponse(
        UUID phaseId,
        List<GroupStandings> groups
) {

    /**
     * Standings for a single group.
     *
     * @param groupNumber the 1-indexed group number within the phase
     * @param rankings    ordered list of team rankings (position 1 = best)
     */
    public record GroupStandings(int groupNumber, List<TeamRanking> rankings) {}

    /**
     * A single team's ranking within its group.
     *
     * @param position  1-indexed rank within the group (1 = best)
     * @param teamName  human-readable team name (from {@code team.description})
     * @param points    accumulated match points
     * @param setsWon   sets won
     * @param setsLost  sets lost
     * @param ballsWon  individual points won across all sets
     * @param ballsLost individual points lost across all sets
     */
    public record TeamRanking(
            int position,
            String teamName,
            int points,
            int setsWon,
            int setsLost,
            int ballsWon,
            int ballsLost
    ) {}
}
