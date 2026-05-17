// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.activity.internal;

import de.vvwt.tm.tournament.activity.ActivityAssignment;
import de.vvwt.tm.tournament.activity.ActivityType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Implements the {@code FIRST_FREE_ROUND} activity assignment algorithm.
 *
 * <p>This class is package-private: it is an implementation detail of {@link
 * DefaultActivityAssignmentService} and must not be used directly by other packages.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code
 * de.vvwt.tm.domain.activity.FirstFreeRoundAssigner}. Behavior byte-equivalent.
 *
 * @see DefaultActivityAssignmentService
 */
final class FirstFreeRoundAssigner {

    FirstFreeRoundResult assign(
            ActivityType activityType,
            LapSchedule lapSchedule,
            int totalLapCount,
            Set<UUID> allTeamIds) {

        if (activityType == null)
            throw new IllegalArgumentException("activityType must not be null");
        if (lapSchedule == null) throw new IllegalArgumentException("lapSchedule must not be null");
        if (totalLapCount < 1) throw new IllegalArgumentException("totalLapCount must be >= 1");
        if (allTeamIds == null) throw new IllegalArgumentException("allTeamIds must not be null");

        Integer capacityPerRound = activityType.getCapacityPerRound();

        TreeSet<UUID> remaining = new TreeSet<>(allTeamIds);
        List<ActivityAssignment> assignments = new ArrayList<>(allTeamIds.size());

        for (int lap = 1; lap <= totalLapCount && !remaining.isEmpty(); lap++) {
            List<UUID> freeInLap = new ArrayList<>();
            for (UUID teamId : remaining) {
                if (!lapSchedule.isBusy(lap, teamId)) {
                    freeInLap.add(teamId);
                }
            }

            int assignableCount;
            if (capacityPerRound == null) {
                assignableCount = freeInLap.size();
            } else {
                assignableCount = Math.min(freeInLap.size(), capacityPerRound);
            }

            String activityName = activityType.getName();
            for (int i = 0; i < assignableCount; i++) {
                UUID teamId = freeInLap.get(i);
                assignments.add(new ActivityAssignment(teamId, lap, activityName));
                remaining.remove(teamId);
            }
        }

        Set<UUID> unassigned = new HashSet<>(remaining);
        return new FirstFreeRoundResult(assignments, unassigned);
    }

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
