package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.PhaseLifecycleService.PhaseScheduleMatch;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST representation of one match in the schedule grid (AC6 — E05S07).
 */
public record ScheduleMatchResponse(
        UUID matchId,
        Integer fieldNumber,
        String team1Description,
        String team2Description,
        String refereeDescription,
        String matchState,
        List<SetResultSummary> setResults
) {
    /**
     * Converts a domain schedule match to a REST response.
     *
     * @param match the domain match (must not be {@code null})
     * @return the REST response
     */
    public static ScheduleMatchResponse from(PhaseScheduleMatch match) {
        List<SetResultSummary> setResultSummaries = match.setResults().stream()
                .map(SetResultSummary::from)
                .collect(Collectors.toList());
        return new ScheduleMatchResponse(
                match.matchId(),
                match.fieldNumber(),
                match.team1Description(),
                match.team2Description(),
                match.refereeDescription(),
                match.matchState(),
                setResultSummaries
        );
    }
}
