package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.RefereeAssignmentService.TeamAssignmentSummary;

import java.util.UUID;

/**
 * REST representation of the per-team referee assignment count (E05S09 AC7).
 *
 * @param teamId        the team UUID
 * @param teamName      the team display name
 * @param assignedCount number of matches this team is assigned to referee
 */
public record TeamAssignmentSummaryResponse(
        UUID teamId,
        String teamName,
        int assignedCount
) {
    /**
     * Converts a domain team assignment summary to a REST response.
     *
     * @param summary the domain summary (must not be {@code null})
     * @return the REST response
     */
    public static TeamAssignmentSummaryResponse from(TeamAssignmentSummary summary) {
        return new TeamAssignmentSummaryResponse(
                summary.teamId(),
                summary.teamName(),
                summary.assignedCount()
        );
    }
}
