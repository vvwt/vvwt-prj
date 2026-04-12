package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.LiveMonitoringService.CurrentLapSummary;

import java.util.Map;

/**
 * REST response for {@code GET /api/phases/{phaseId}/current-lap} (AC4 — E05S10).
 *
 * @see de.vvwt.tm.infrastructure.web.MonitoringController
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
public record CurrentLapSummaryResponse(
        int currentLapNumber,
        int totalLapCount,
        /** Match counts keyed by state name (e.g., "ENABLED", "INPROGRESS", "FINISHED_WINNER1"). */
        Map<String, Long> matchCountByState,
        /** Phase status: PENDING, ACTIVE, or COMPLETED. */
        String phaseStatus
) {

    /**
     * Converts a domain current lap summary to a REST response.
     *
     * @param summary the domain summary (must not be {@code null})
     * @return the REST response
     */
    public static CurrentLapSummaryResponse from(CurrentLapSummary summary) {
        return new CurrentLapSummaryResponse(
                summary.currentLapNumber(),
                summary.totalLapCount(),
                summary.matchCountByState(),
                summary.phaseStatus()
        );
    }
}
