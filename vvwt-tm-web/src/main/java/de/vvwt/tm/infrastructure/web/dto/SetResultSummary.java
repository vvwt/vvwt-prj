package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.SetResult;

/**
 * Summary of a single set result for inclusion in the schedule response (AC6 — E05S07).
 */
public record SetResultSummary(
        int setIndex,
        int team1Points,
        int team2Points
) {
    /**
     * Converts a {@link SetResult} entity to a summary DTO.
     *
     * @param sr the set result (must not be {@code null})
     * @return the summary
     */
    public static SetResultSummary from(SetResult sr) {
        return new SetResultSummary(sr.getSetIndex(), sr.getTeam1Points(), sr.getTeam2Points());
    }
}
