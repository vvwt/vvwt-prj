package de.vvwt.tm.infrastructure.web.dto;

/**
 * Request body for PUT /api/tournaments/{tournamentId}/teams/{id} (AC3 — E05S05).
 *
 * <p>All fields are optional. {@code null} means "no change" for {@code description}.
 * For flags and team_number the current values are always replaced with the request values.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story E05S05</a>
 */
public record TeamUpdateRequest(

        /** New team name (applied if not {@code null}). */
        String description,

        /**
         * New team number (applied if &gt; 0; 0 or null = no change).
         * Duplicate numbers return 409 (AC10).
         */
        Integer teamNumber,

        /** New participate flag (required — always applied). */
        Boolean participate,

        /** New refereeAssignment flag (required — always applied). */
        Boolean refereeAssignment,

        /** New withoutAssessment flag (required — always applied). */
        Boolean withoutAssessment
) {
    /** Resolves teamNumber to int — 0 signals "no change". */
    public int resolvedTeamNumber() {
        return teamNumber != null && teamNumber >= 1 ? teamNumber : 0;
    }

    /** Resolves participate, defaulting to true. */
    public boolean resolvedParticipate() {
        return participate == null || participate;
    }

    /** Resolves refereeAssignment, defaulting to false. */
    public boolean resolvedRefereeAssignment() {
        return refereeAssignment != null && refereeAssignment;
    }

    /** Resolves withoutAssessment, defaulting to false. */
    public boolean resolvedWithoutAssessment() {
        return withoutAssessment != null && withoutAssessment;
    }
}
