package de.vvwt.tm.domain;

import java.util.List;
import java.util.UUID;

/**
 * Value object representing a complete mapping suggestion for Phase 2+ team distribution.
 *
 * <p>Contains:
 * <ul>
 *   <li>{@link #sourcePhaseId()} — the previous (COMPLETED) phase</li>
 *   <li>{@link #targetPhaseId()} — the PENDING phase being mapped into</li>
 *   <li>{@link #sourceGroups()} — previous phase standings by group (for the "source" panel)</li>
 *   <li>{@link #suggestedAssignments()} — suggested target group assignments (for the "target" panel)</li>
 * </ul>
 *
 * @see PhaseMappingService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S08.story.md">Story E05S08 AC1</a>
 */
public record MappingSuggestion(
        UUID sourcePhaseId,
        UUID targetPhaseId,
        List<SourceGroup> sourceGroups,
        List<TargetAssignment> suggestedAssignments
) {

    /**
     * One source group with its team standings (for the left / "source" panel in the UI).
     *
     * @param groupNumber 1-indexed group number in the previous phase
     * @param teams       teams in this group, sorted by D-33 standings (best first)
     */
    public record SourceGroup(int groupNumber, List<SourceTeam> teams) {}

    /**
     * One team entry in the source group, carrying enough data to display standings.
     *
     * @param teamId            UUID of the team entity
     * @param avatarId          UUID of the TeamAvatar in the previous phase
     * @param description       human-readable avatar label (may be null)
     * @param points            accumulated points
     * @param setsWon           sets won
     * @param setsLost          sets lost
     * @param withoutAssessment true if this team is excluded from standings (D-26)
     */
    public record SourceTeam(
            UUID teamId,
            UUID avatarId,
            String description,
            int points,
            int setsWon,
            int setsLost,
            boolean withoutAssessment
    ) {}

    /**
     * One suggested target slot assignment: team → (group, position) in the new phase.
     *
     * @param teamId        the team to place
     * @param groupNumber   target group number (1-indexed)
     * @param groupPosition target position within the group (1-indexed)
     */
    public record TargetAssignment(UUID teamId, int groupNumber, int groupPosition) {}
}
