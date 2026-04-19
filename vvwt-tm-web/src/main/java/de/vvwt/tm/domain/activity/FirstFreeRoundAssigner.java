package de.vvwt.tm.domain.activity;

import de.vvwt.tm.domain.ActivityType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Implements the {@code FIRST_FREE_ROUND} activity assignment algorithm.
 *
 * <h2>Algorithm (E08S04 story context)</h2>
 *
 * <ol>
 *   <li>Iterate laps in order (lap 1, 2, 3, … {@code totalLapCount}).
 *   <li>For each lap, identify free teams: teams that are not playing and not refereeing, that do
 *       not yet have this activity assigned, sorted by team UUID ascending for determinism (AC7).
 *   <li>Assign up to {@code capacityPerRound} teams (or all free teams if capacity is {@code
 *       null}/unlimited) — AC2, AC3.
 *   <li>Remaining unassigned teams carry over to the next lap — AC3.
 *   <li>Continue until all teams are assigned or no more laps remain — AC6.
 * </ol>
 *
 * <p>Teams that cannot be assigned (no free round, or capacity exhausted across all laps) are
 * returned in the {@code unassigned} set. No exception is thrown — the caller (E08S06) surfaces
 * unassigned teams in the preview UI (AC6).
 *
 * <p>This class is package-private: it is an implementation detail of {@link
 * ActivityAssignmentServiceImpl} and must not be used directly by other packages.
 *
 * @see ActivityAssignmentServiceImpl
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S04.story.md">Story
 *     E08S04 AC2–AC7</a>
 */
final class FirstFreeRoundAssigner {

    /**
     * Computes assignments for a single activity type using the FIRST_FREE_ROUND rule.
     *
     * @param activityType the activity type being assigned (NOT NULL)
     * @param lapSchedule the playing/refereeing schedule per lap (NOT NULL)
     * @param totalLapCount total number of laps; upper bound of the search horizon (must be &ge; 1)
     * @param allTeamIds all team IDs to assign (NOT NULL; may not be empty)
     * @return result containing the ordered assignment list and the set of unassigned team IDs
     */
    FirstFreeRoundResult assign(
            ActivityType activityType,
            LapSchedule lapSchedule,
            int totalLapCount,
            Set<UUID> allTeamIds) {

        // Input guards
        if (activityType == null)
            throw new IllegalArgumentException("activityType must not be null");
        if (lapSchedule == null) throw new IllegalArgumentException("lapSchedule must not be null");
        if (totalLapCount < 1) throw new IllegalArgumentException("totalLapCount must be >= 1");
        if (allTeamIds == null) throw new IllegalArgumentException("allTeamIds must not be null");

        Integer capacityPerRound = activityType.getCapacityPerRound(); // null = unlimited

        // Teams still waiting for an assignment (initially all teams, sorted by UUID for
        // determinism AC7)
        // We use a stable ordered structure: a sorted list from which we drain as assignments are
        // made.
        // TreeSet gives natural UUID ordering (ascending), satisfying the determinism requirement.
        TreeSet<UUID> remaining = new TreeSet<>(allTeamIds);

        List<ActivityAssignment> assignments = new ArrayList<>(allTeamIds.size());

        for (int lap = 1; lap <= totalLapCount && !remaining.isEmpty(); lap++) {
            // Free teams for this lap: teams that are not busy AND not yet assigned
            // Collect in sorted order (TreeSet iteration is ascending by natural order)
            List<UUID> freeInLap = new ArrayList<>();
            for (UUID teamId : remaining) {
                if (!lapSchedule.isBusy(lap, teamId)) {
                    freeInLap.add(teamId);
                }
            }

            // Apply capacity limit
            int assignableCount;
            if (capacityPerRound == null) {
                // unlimited
                assignableCount = freeInLap.size();
            } else {
                assignableCount = Math.min(freeInLap.size(), capacityPerRound);
            }

            // Assign the first assignableCount free teams in this lap
            String activityName = activityType.getName();
            for (int i = 0; i < assignableCount; i++) {
                UUID teamId = freeInLap.get(i);
                assignments.add(new ActivityAssignment(teamId, lap, activityName));
                remaining.remove(teamId);
            }
        }

        // Any team still in `remaining` could not be assigned
        Set<UUID> unassigned = new HashSet<>(remaining);

        return new FirstFreeRoundResult(assignments, unassigned);
    }

    /**
     * Intermediate result of a single {@link FirstFreeRoundAssigner#assign} call.
     *
     * <p>Package-private — consumed only by {@link ActivityAssignmentServiceImpl}.
     */
    static final class FirstFreeRoundResult {

        private final List<ActivityAssignment> assignments;
        private final Set<UUID> unassignedTeams;

        FirstFreeRoundResult(List<ActivityAssignment> assignments, Set<UUID> unassignedTeams) {
            this.assignments = assignments;
            this.unassignedTeams = unassignedTeams;
        }

        List<ActivityAssignment> getAssignments() {
            return assignments;
        }

        Set<UUID> getUnassignedTeams() {
            return unassignedTeams;
        }
    }
}
