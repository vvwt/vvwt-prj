package de.vvwt.tm.tournament.activity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spring bean interface for the activity assignment service (E08S04 AC1).
 *
 * <p>Computes which team gets which activity in which lap, globally across all teams. The
 * computation is on-demand — results are never persisted (E08S04 Out of Scope).
 *
 * <p>This interface is the public Modulith API surface for {@code tournament.activity} (DEC-35
 * hexagonal-pragma). The canonical implementation is {@link
 * de.vvwt.tm.tournament.activity.internal.DefaultActivityAssignmentService}.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code
 * de.vvwt.tm.domain.activity.ActivityAssignmentService} into {@code tournament.activity} public
 * surface per DEC-21 + DEC-35.
 *
 * @see ActivityAssignmentResult
 * @see UnsupportedAssignmentRuleException
 */
public interface ActivityAssignmentService {

    /**
     * Assigns activities to teams across all laps of a phase.
     *
     * <p>Each activity type is processed independently. A team may receive multiple different
     * activities in the same lap (AC5).
     *
     * @param activityTypes activity types to assign; must not be null; may be empty (returns empty
     *     result)
     * @param matchSchedule lap → set of team IDs playing in that lap; must not be null
     * @param refereeSchedule lap → set of team IDs refereeing in that lap; must not be null
     * @param totalLapCount total number of laps; must be &ge; 1
     * @param allTeamIds the full set of team IDs participating; must not be null
     * @return assignment result containing assigned and unassigned teams per activity type; never
     *     null
     * @throws IllegalArgumentException if any required parameter is null or totalLapCount &lt; 1
     * @throws UnsupportedAssignmentRuleException if an activity type has an unrecognized assignment
     *     rule (AC8)
     */
    ActivityAssignmentResult assignActivities(
            List<ActivityType> activityTypes,
            Map<Integer, Set<UUID>> matchSchedule,
            Map<Integer, Set<UUID>> refereeSchedule,
            int totalLapCount,
            Set<UUID> allTeamIds);
}
