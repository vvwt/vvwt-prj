package de.vvwt.tm.domain.activity;

import de.vvwt.tm.domain.ActivityType;
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
 * <h2>Inputs</h2>
 *
 * <ul>
 *   <li>{@code activityTypes} — the activity types to assign, each carrying its own rule ({@link
 *       de.vvwt.tm.domain.AssignmentRule}) and optional capacity limit.
 *   <li>{@code matchSchedule} — maps each lap number to the set of team IDs that are
 *       <em>playing</em> in that lap. Derived from the slot-optimized match schedule (E03S09).
 *   <li>{@code refereeSchedule} — maps each lap number to the set of team IDs that are
 *       <em>refereeing</em> in that lap (E03S10). A refereeing team is not free even though it is
 *       not playing.
 *   <li>{@code totalLapCount} — the total number of laps in the phase. Caps the search horizon:
 *       teams not assigned within laps 1..{@code totalLapCount} are reported as unassigned.
 *   <li>{@code allTeamIds} — the complete set of team IDs to assign. Needed because some teams may
 *       be free in every lap and would otherwise not appear in either schedule map.
 * </ul>
 *
 * <h2>Security (AC10)</h2>
 *
 * <p>The service operates on data already filtered by tenant context. No direct database queries
 * are made here — inputs are pre-loaded by the caller (E08S06 REST endpoint).
 *
 * @see ActivityAssignmentResult
 * @see UnsupportedAssignmentRuleException
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S04.story.md">Story
 *     E08S04 AC1</a>
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
