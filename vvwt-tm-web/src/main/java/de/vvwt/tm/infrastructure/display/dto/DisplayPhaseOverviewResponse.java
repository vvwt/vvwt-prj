package de.vvwt.tm.infrastructure.display.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/display/overview} (E07S04, AC1).
 *
 * <p>Describes the current active phase for a display device's tenant, including phase metadata and
 * a summary of all groups (group number + team count). A {@code preparationPreview} flag is set
 * when the phase is in PREPARATION status and slot-optimization has already generated matches
 * (AC6).
 *
 * <h2>E07S06 — tenantId field</h2>
 *
 * <p>The {@code tenantId} field was added so the display SPA can build the tenant-scoped WebSocket
 * topic {@code /topic/display/{tenantId}/events} (E07S06 AC1, AC11). The token already determines
 * the tenant on the server; this field just sends it back so the client can subscribe to the
 * correct topic without additional round-trips.
 *
 * @see de.vvwt.tm.infrastructure.display.DisplayOverviewService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S06.story.md">Story
 *     E07S06</a>
 */
public record DisplayPhaseOverviewResponse(
        UUID phaseId,
        UUID tenantId,
        String phaseName,
        String phaseStatus,
        int lapCount,
        int currentLap,
        int fieldCount,
        boolean preparationPreview,
        List<GroupSummary> groups) {

    /**
     * Summary of a single group within the current phase.
     *
     * @param groupNumber the 1-indexed group number within the phase
     * @param teamCount the number of teams registered in this group
     */
    public record GroupSummary(int groupNumber, int teamCount) {}
}
