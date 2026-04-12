package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.RefereeAssignmentService.RefereeAssignmentOverview;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST response for the referee assignment overview (E05S09 AC1, AC4).
 *
 * <p>Contains all matches with their current referee assignments, a per-team assignment
 * summary for the distribution view (AC7), and the full list of eligible referee teams
 * for the override dropdown (AC6).
 *
 * @param assignments      per-match assignment entries, sorted by lapNumber then fieldNumber
 * @param teamSummary      per-team assignment count, sorted by count descending (AC7)
 * @param allRefereeTeams  all teams with {@code refereeAssignment=true} in the phase (AC6)
 */
public record RefereeAssignmentOverviewResponse(
        List<RefereeAssignmentEntryResponse> assignments,
        List<TeamAssignmentSummaryResponse> teamSummary,
        List<RefereeTeamOptionResponse> allRefereeTeams
) {
    /**
     * Converts a domain assignment overview to a REST response.
     *
     * @param overview the domain overview (must not be {@code null})
     * @return the REST response
     */
    public static RefereeAssignmentOverviewResponse from(RefereeAssignmentOverview overview) {
        List<RefereeAssignmentEntryResponse> assignments = overview.assignments().stream()
                .map(RefereeAssignmentEntryResponse::from)
                .collect(Collectors.toList());

        List<TeamAssignmentSummaryResponse> teamSummary = overview.teamSummary().stream()
                .map(TeamAssignmentSummaryResponse::from)
                .collect(Collectors.toList());

        List<RefereeTeamOptionResponse> allRefereeTeams = overview.allRefereeTeams().stream()
                .map(RefereeTeamOptionResponse::from)
                .collect(Collectors.toList());

        return new RefereeAssignmentOverviewResponse(assignments, teamSummary, allRefereeTeams);
    }
}
