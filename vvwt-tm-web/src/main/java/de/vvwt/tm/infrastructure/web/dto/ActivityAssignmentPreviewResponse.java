package de.vvwt.tm.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response body for GET /api/tournaments/{tournamentId}/activity-assignments (E08S06, AC2).
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * {
 *   "phaseId": "...",
 *   "assignments": [
 *     {
 *       "activityTypeName": "Mannschaftsfoto",
 *       "entries": [
 *         { "lapNumber": 1, "teams": [{ "teamId": "...", "teamNumber": 3, "teamName": "Team C" }] }
 *       ]
 *     }
 *   ],
 *   "unassigned": [
 *     { "activityTypeName": "Mannschaftsfoto", "teams": [] }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code phaseId} is {@code null} when no phase is available yet (AC6 — no phase prepared).
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
public record ActivityAssignmentPreviewResponse(

        /** Phase for which assignments were computed; {@code null} when no prepared phase exists. */
        UUID phaseId,

        /**
         * Assignments per activity type. One entry per activity type with sub-entries per lap.
         * Empty when no activity types are configured or no phase is prepared (AC6).
         */
        List<ActivityTypeAssignment> assignments,

        /**
         * Teams that could not be assigned per activity type (AC5 — unassigned warning).
         * An empty {@code teams} list means all teams were successfully assigned.
         */
        List<UnassignedEntry> unassigned
) {

    /** One entry per activity type — groups lap-level entries. */
    public record ActivityTypeAssignment(
            String activityTypeName,
            List<LapEntry> entries
    ) {}

    /** One entry per lap that has at least one assignment. */
    public record LapEntry(
            int lapNumber,
            List<TeamRef> teams
    ) {}

    /** A team reference with human-readable number and name (for display in the preview table). */
    public record TeamRef(
            UUID teamId,
            int teamNumber,
            String teamName
    ) {}

    /** Teams that could not be assigned for a given activity type. */
    public record UnassignedEntry(
            String activityTypeName,
            List<TeamRef> teams
    ) {}
}
