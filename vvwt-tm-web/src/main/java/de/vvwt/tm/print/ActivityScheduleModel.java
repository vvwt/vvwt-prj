package de.vvwt.tm.print;

import java.util.Collections;
import java.util.List;

/**
 * Assembled data model for the Mannschaftsfoto-Übersicht print template.
 *
 * <p>Returned by {@link ActivityScheduleAssembler#assemble} and consumed by the REST controller
 * (added in E24S06) to populate the Mustache template.
 *
 * @param rows ordered list of table rows (data rows + break separators); never null
 * @param totalAssignedTeams total count of team assignments across all rounds
 * @param roundCount count of non-empty rounds (rounds with ≥ 1 assignment)
 * @param unassignedTeamNames sorted list of display names for teams that could not be assigned
 * @param hasTime {@code true} if the tournament has a planned start time
 * @see ActivityScheduleRow
 * @see ActivityScheduleAssembler
 * @since E24S03
 */
public record ActivityScheduleModel(
        List<ActivityScheduleRow> rows,
        int totalAssignedTeams,
        int roundCount,
        List<String> unassignedTeamNames,
        boolean hasTime) {

    /**
     * Returns an empty model for error paths (no phases, no teams).
     *
     * @return empty model with zero counts and empty lists
     */
    public static ActivityScheduleModel empty() {
        return new ActivityScheduleModel(
                Collections.emptyList(), 0, 0, Collections.emptyList(), false);
    }

    /** {@code true} if any team could not be assigned. */
    public boolean hasUnassigned() {
        return !unassignedTeamNames.isEmpty();
    }
}
