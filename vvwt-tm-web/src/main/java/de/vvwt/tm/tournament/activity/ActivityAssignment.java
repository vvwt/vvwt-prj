package de.vvwt.tm.tournament.activity;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing a single activity assignment.
 *
 * <p>Records that a specific team ({@code teamId}) is assigned to perform an activity (identified
 * by {@code activityTypeName}) during the given {@code lapNumber}.
 *
 * <p>Assignments are computed on demand by {@link ActivityAssignmentService} — they are never
 * persisted (see E08S04 Out of Scope).
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code
 * de.vvwt.tm.domain.activity.ActivityAssignment} into {@code tournament.activity} public surface
 * per DEC-21 + DEC-35. This type crosses module boundaries (print context, web context) so it must
 * be at the public package level.
 *
 * @see ActivityAssignmentResult
 * @see ActivityAssignmentService
 */
public final class ActivityAssignment {

    /** The team to which the activity is assigned. Never null. */
    private final UUID teamId;

    /**
     * The lap (round) number during which the team performs the activity. 1-indexed, consistent
     * with {@code Match.lapNumber}.
     */
    private final int lapNumber;

    /**
     * The human-readable name of the activity type (e.g., "Mannschaftsfoto"). Denormalised from
     * {@link ActivityType#getName()} for convenience in print templates (E08S08, E08S09).
     */
    private final String activityTypeName;

    /**
     * Constructs an activity assignment.
     *
     * @param teamId team receiving the assignment (NOT NULL)
     * @param lapNumber lap during which the activity occurs (must be &ge; 1)
     * @param activityTypeName human-readable activity name (NOT NULL)
     */
    public ActivityAssignment(UUID teamId, int lapNumber, String activityTypeName) {
        if (teamId == null) {
            throw new IllegalArgumentException("teamId must not be null");
        }
        if (lapNumber < 1) {
            throw new IllegalArgumentException("lapNumber must be >= 1, got " + lapNumber);
        }
        if (activityTypeName == null || activityTypeName.isBlank()) {
            throw new IllegalArgumentException("activityTypeName must not be null or blank");
        }
        this.teamId = teamId;
        this.lapNumber = lapNumber;
        this.activityTypeName = activityTypeName;
    }

    /** Returns the assigned team's UUID. */
    public UUID getTeamId() {
        return teamId;
    }

    /** Returns the lap number during which the team performs the activity. */
    public int getLapNumber() {
        return lapNumber;
    }

    /** Returns the activity type name. */
    public String getActivityTypeName() {
        return activityTypeName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ActivityAssignment other)) return false;
        return lapNumber == other.lapNumber
                && teamId.equals(other.teamId)
                && activityTypeName.equals(other.activityTypeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, lapNumber, activityTypeName);
    }

    @Override
    public String toString() {
        return "ActivityAssignment{teamId="
                + teamId
                + ", lapNumber="
                + lapNumber
                + ", activityTypeName='"
                + activityTypeName
                + "'}";
    }
}
