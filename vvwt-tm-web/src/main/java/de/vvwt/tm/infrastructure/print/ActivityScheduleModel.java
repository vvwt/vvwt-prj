package de.vvwt.tm.infrastructure.print;

import java.util.Collections;
import java.util.List;

/**
 * Assembled data model for the Mannschaftsfoto-Übersicht print template (E08S09).
 *
 * <p>Returned by {@link ActivityScheduleAssembler#assemble} and consumed by
 * {@link PrintController} to populate the Mustache model.
 *
 * @param rows              ordered list of table rows (data rows + break separators); never null
 * @param totalAssignedTeams total count of team assignments across all rounds (AC5)
 * @param roundCount        count of non-empty rounds (rounds with ≥ 1 assignment) (AC5)
 * @param unassignedTeamNames sorted list of display names for teams that could not be assigned (AC6)
 * @param hasTime           {@code true} if the tournament has a planned start time (AC7)
 *
 * @see ActivityScheduleRow
 * @see ActivityScheduleAssembler
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S09.story.md">Story E08S09</a>
 */
public record ActivityScheduleModel(
        List<ActivityScheduleRow> rows,
        int totalAssignedTeams,
        int roundCount,
        List<String> unassignedTeamNames,
        boolean hasTime
) {

    /**
     * Returns an empty model for error paths (no phases, no teams).
     *
     * @return empty model with zero counts and empty lists
     */
    public static ActivityScheduleModel empty() {
        return new ActivityScheduleModel(
                Collections.emptyList(),
                0,
                0,
                Collections.emptyList(),
                false);
    }

    /** {@code true} if any team could not be assigned (AC6 warning section guard). */
    public boolean hasUnassigned() {
        return !unassignedTeamNames.isEmpty();
    }
}
