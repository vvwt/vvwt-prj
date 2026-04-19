package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/tournaments/{tournamentId}/teams (AC2 — E05S05).
 *
 * <p>{@code teamNumber} is optional. If omitted (null or absent) it defaults to 0, which signals
 * the service to auto-assign max+1 (AC2).
 *
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story
 *     E05S05</a>
 */
public record TeamCreateRequest(

        /** Team name or label (required). */
        @NotBlank(message = "description is required") String description,

        /**
         * Team number within the tournament (optional). If null or absent, auto-assigned as
         * max(existing) + 1. Minimum value 1 when provided.
         */
        Integer teamNumber,

        /** Whether the team actively participates (default: true). */
        Boolean participate,

        /** Whether the team provides a referee (default: false). */
        Boolean refereeAssignment,

        /** Whether the team is excluded from standings (default: false). */
        Boolean withoutAssessment) {
    /**
     * Resolves {@code teamNumber} to an int for service consumption. Returns 0 when not provided,
     * signalling auto-assignment.
     */
    public int resolvedTeamNumber() {
        return teamNumber != null && teamNumber >= 1 ? teamNumber : 0;
    }

    /** Returns {@code participate}, defaulting to {@code true} when null. */
    public boolean resolvedParticipate() {
        return participate == null || participate;
    }

    /** Returns {@code refereeAssignment}, defaulting to {@code false} when null. */
    public boolean resolvedRefereeAssignment() {
        return refereeAssignment != null && refereeAssignment;
    }

    /** Returns {@code withoutAssessment}, defaulting to {@code false} when null. */
    public boolean resolvedWithoutAssessment() {
        return withoutAssessment != null && withoutAssessment;
    }
}
