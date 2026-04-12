package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MatchState;

import java.util.Map;

/**
 * Match count breakdown by state for a phase (AC2 — E05S07).
 */
public record PhaseMatchCountsResponse(
        long open,
        long enabled,
        long inProgress,
        long finishedStandoff,
        long finishedWinner1,
        long finishedWinner2,
        long canceled,
        long total
) {
    /**
     * Builds the response from a state → count map (as returned by
     * {@link de.vvwt.tm.domain.PhaseLifecycleService#buildMatchCounts(java.util.UUID)}).
     *
     * @param counts map from MatchState to count; missing states are treated as 0
     * @return the response
     */
    public static PhaseMatchCountsResponse from(Map<MatchState, Long> counts) {
        long open             = counts.getOrDefault(MatchState.OPEN,             0L);
        long enabled          = counts.getOrDefault(MatchState.ENABLED,          0L);
        long inProgress       = counts.getOrDefault(MatchState.INPROGRESS,       0L);
        long finishedStandoff = counts.getOrDefault(MatchState.FINISHED_STANDOFF, 0L);
        long finishedWinner1  = counts.getOrDefault(MatchState.FINISHED_WINNER1,  0L);
        long finishedWinner2  = counts.getOrDefault(MatchState.FINISHED_WINNER2,  0L);
        long canceled         = counts.getOrDefault(MatchState.CANCELED,         0L);
        long total            = open + enabled + inProgress + finishedStandoff
                                + finishedWinner1 + finishedWinner2 + canceled;
        return new PhaseMatchCountsResponse(
                open, enabled, inProgress, finishedStandoff,
                finishedWinner1, finishedWinner2, canceled, total);
    }
}
