package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MappingSuggestion;

import java.util.List;
import java.util.UUID;

/**
 * REST response DTO for the mapping suggestion endpoint (E05S08 AC1).
 *
 * <p>Mirrors the structure of {@link MappingSuggestion} but uses plain Java types
 * suitable for JSON serialization via Jackson.
 *
 * @param sourcePhaseId        UUID of the previous (COMPLETED) phase
 * @param targetPhaseId        UUID of the PENDING phase being mapped into
 * @param sourceGroups         previous phase standings by group (for the "source" panel)
 * @param suggestedAssignments suggested target slot assignments (for the "target" panel)
 */
public record MappingSuggestionResponse(
        UUID sourcePhaseId,
        UUID targetPhaseId,
        List<SourceGroupResponse> sourceGroups,
        List<TargetAssignmentResponse> suggestedAssignments
) {

    /**
     * Constructs a {@link MappingSuggestionResponse} from the domain {@link MappingSuggestion}.
     *
     * @param suggestion the domain value object
     * @return the DTO
     */
    public static MappingSuggestionResponse from(MappingSuggestion suggestion) {
        List<SourceGroupResponse> sourceGroups = suggestion.sourceGroups().stream()
                .map(g -> new SourceGroupResponse(
                        g.groupNumber(),
                        g.teams().stream()
                                .map(t -> new SourceTeamResponse(
                                        t.teamId(),
                                        t.avatarId(),
                                        t.description(),
                                        t.points(),
                                        t.setsWon(),
                                        t.setsLost(),
                                        t.withoutAssessment()))
                                .toList()))
                .toList();

        List<TargetAssignmentResponse> assignments = suggestion.suggestedAssignments().stream()
                .map(a -> new TargetAssignmentResponse(a.teamId(), a.groupNumber(), a.groupPosition()))
                .toList();

        return new MappingSuggestionResponse(
                suggestion.sourcePhaseId(),
                suggestion.targetPhaseId(),
                sourceGroups,
                assignments);
    }

    /**
     * Source group with team standings.
     *
     * @param groupNumber 1-indexed group number in the previous phase
     * @param teams       teams in standings order (best first)
     */
    public record SourceGroupResponse(int groupNumber, List<SourceTeamResponse> teams) {}

    /**
     * One team entry in the source group standings.
     *
     * @param teamId            team UUID
     * @param avatarId          TeamAvatar UUID in the previous phase
     * @param description       avatar description (may be null)
     * @param points            accumulated points
     * @param setsWon           sets won
     * @param setsLost          sets lost
     * @param withoutAssessment true if this team is excluded from competitive standings (D-26)
     */
    public record SourceTeamResponse(
            UUID teamId,
            UUID avatarId,
            String description,
            int points,
            int setsWon,
            int setsLost,
            boolean withoutAssessment
    ) {}

    /**
     * One suggested target slot assignment.
     *
     * @param teamId        team to place
     * @param groupNumber   target group (1-indexed)
     * @param groupPosition target position within group (1-indexed)
     */
    public record TargetAssignmentResponse(UUID teamId, int groupNumber, int groupPosition) {}
}
