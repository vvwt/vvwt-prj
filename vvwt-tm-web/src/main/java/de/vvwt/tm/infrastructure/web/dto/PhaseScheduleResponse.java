package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.PhaseLifecycleService.PhaseScheduleLap;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST response for {@code GET /api/phases/{phaseId}/schedule} (AC6 — E05S07).
 *
 * <p>The schedule is organized as a list of laps, each containing the matches
 * for that lap ordered by field number. This allows the UI to render a grid
 * (rows = laps, columns = fields) as specified in AC9.
 */
public record PhaseScheduleResponse(
        UUID phaseId,
        List<LapResponse> laps
) {
    /**
     * One lap in the schedule.
     *
     * @param lapNumber the lap index
     * @param matches   matches in this lap, ordered by fieldNumber
     */
    public record LapResponse(
            int lapNumber,
            List<ScheduleMatchResponse> matches
    ) {
        /**
         * Converts a domain lap to a REST response.
         *
         * @param lap the domain lap (must not be {@code null})
         * @return the REST response
         */
        public static LapResponse from(PhaseScheduleLap lap) {
            List<ScheduleMatchResponse> matchResponses = lap.matches().stream()
                    .map(ScheduleMatchResponse::from)
                    .collect(Collectors.toList());
            return new LapResponse(lap.lapNumber(), matchResponses);
        }
    }

    /**
     * Converts a list of domain schedule laps to a REST response.
     *
     * @param phaseId the phase ID
     * @param laps    the domain laps (must not be {@code null})
     * @return the REST response
     */
    public static PhaseScheduleResponse from(UUID phaseId, List<PhaseScheduleLap> laps) {
        List<LapResponse> lapResponses = laps.stream()
                .map(LapResponse::from)
                .collect(Collectors.toList());
        return new PhaseScheduleResponse(phaseId, lapResponses);
    }
}
