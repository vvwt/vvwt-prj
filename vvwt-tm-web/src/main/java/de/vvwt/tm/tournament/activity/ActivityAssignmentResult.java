package de.vvwt.tm.tournament.activity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable result of an {@link ActivityAssignmentService#assignActivities} call.
 *
 * <p>Groups all activity assignments by activity type and records which teams could not be assigned
 * (because no free lap was available within the total lap count).
 *
 * <p>Consuming code (print templates E08S08, E08S09; preview REST endpoint E08S06) reads this
 * result to build schedules.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code de.vvwt.tm.domain.activity} into {@code
 * tournament.activity} public surface per DEC-21 + DEC-35.
 *
 * @see ActivityAssignment
 * @see ActivityAssignmentService
 */
public final class ActivityAssignmentResult {

    /**
     * Assignments per activity type. Key: the {@link ActivityType} entity. Value: list of
     * assignments for that type, in lap order.
     */
    private final Map<ActivityType, List<ActivityAssignment>> assignments;

    /**
     * Teams that could not be assigned per activity type. Key: the {@link ActivityType} entity.
     * Value: set of team UUIDs with no free round. An empty set means all teams were successfully
     * assigned.
     */
    private final Map<ActivityType, Set<UUID>> unassignedTeams;

    /**
     * Constructs the result. Both maps are defensively copied and made unmodifiable.
     *
     * @param assignments assignments per activity type (NOT NULL)
     * @param unassignedTeams unassigned teams per activity type (NOT NULL)
     */
    public ActivityAssignmentResult(
            Map<ActivityType, List<ActivityAssignment>> assignments,
            Map<ActivityType, Set<UUID>> unassignedTeams) {
        if (assignments == null) {
            throw new IllegalArgumentException("assignments must not be null");
        }
        if (unassignedTeams == null) {
            throw new IllegalArgumentException("unassignedTeams must not be null");
        }
        // Defensive copy: preserve insertion order (LinkedHashMap), wrap values as unmodifiable
        Map<ActivityType, List<ActivityAssignment>> assignmentsCopy = new LinkedHashMap<>();
        for (Map.Entry<ActivityType, List<ActivityAssignment>> entry : assignments.entrySet()) {
            assignmentsCopy.put(
                    entry.getKey(), Collections.unmodifiableList(List.copyOf(entry.getValue())));
        }
        this.assignments = Collections.unmodifiableMap(assignmentsCopy);

        Map<ActivityType, Set<UUID>> unassignedCopy = new LinkedHashMap<>();
        for (Map.Entry<ActivityType, Set<UUID>> entry : unassignedTeams.entrySet()) {
            unassignedCopy.put(
                    entry.getKey(), Collections.unmodifiableSet(Set.copyOf(entry.getValue())));
        }
        this.unassignedTeams = Collections.unmodifiableMap(unassignedCopy);
    }

    /**
     * Returns all assignments grouped by activity type.
     *
     * @return unmodifiable map; keys and values are safe to iterate; never null
     */
    public Map<ActivityType, List<ActivityAssignment>> getAssignments() {
        return assignments;
    }

    /**
     * Returns the set of team IDs that could not be assigned per activity type.
     *
     * @return unmodifiable map; an empty inner set means all teams were assigned; never null
     */
    public Map<ActivityType, Set<UUID>> getUnassignedTeams() {
        return unassignedTeams;
    }
}
