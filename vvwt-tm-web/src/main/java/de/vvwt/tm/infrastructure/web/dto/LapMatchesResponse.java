package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.LiveMonitoringService.LapMatchDetail;
import de.vvwt.tm.domain.LiveMonitoringService.LapMatchSetResult;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST response for {@code GET /api/phases/{phaseId}/laps/{lapNumber}/matches} (AC3 — E05S10).
 *
 * @see de.vvwt.tm.infrastructure.web.MonitoringController
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
public record LapMatchesResponse(
        UUID phaseId,
        int lapNumber,
        List<LapMatchResponse> matches
) {

    /**
     * REST representation of one match in a lap (AC3).
     */
    public record LapMatchResponse(
            UUID matchId,
            Integer fieldNumber,
            UUID avatar1Id,
            UUID avatar2Id,
            String team1Description,
            String team2Description,
            String refereeDescription,
            String matchState,
            List<LapSetResultResponse> setResults
    ) {
        /** REST representation of one set result within a match. */
        public record LapSetResultResponse(
                int setIndex,
                int team1Points,
                int team2Points,
                String setState
        ) {
            public static LapSetResultResponse from(LapMatchSetResult sr) {
                return new LapSetResultResponse(sr.setIndex(), sr.team1Points(),
                        sr.team2Points(), sr.setState());
            }
        }

        public static LapMatchResponse from(LapMatchDetail detail) {
            List<LapSetResultResponse> sets = detail.setResults().stream()
                    .map(LapSetResultResponse::from)
                    .collect(Collectors.toList());
            return new LapMatchResponse(
                    detail.matchId(),
                    detail.fieldNumber(),
                    detail.avatar1Id(),
                    detail.avatar2Id(),
                    detail.team1Description(),
                    detail.team2Description(),
                    detail.refereeDescription(),
                    detail.matchState(),
                    sets
            );
        }
    }

    /**
     * Converts a list of domain lap match details to a REST response.
     *
     * @param phaseId   the phase ID
     * @param lapNumber the lap number
     * @param details   the domain details (must not be {@code null})
     * @return the REST response
     */
    public static LapMatchesResponse from(UUID phaseId, int lapNumber,
                                          List<LapMatchDetail> details) {
        List<LapMatchResponse> matches = details.stream()
                .map(LapMatchResponse::from)
                .collect(Collectors.toList());
        return new LapMatchesResponse(phaseId, lapNumber, matches);
    }
}
