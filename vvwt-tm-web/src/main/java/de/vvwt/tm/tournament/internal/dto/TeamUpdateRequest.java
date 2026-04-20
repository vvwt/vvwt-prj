package de.vvwt.tm.tournament.internal.dto;

/**
 * Request body for PUT /api/tm/tournaments/{tournamentId}/teams/{id} (E21S04, AC-TDD-TeamDTOs).
 *
 * <p>All fields are optional — null means "no change". Mirrors {@code
 * de.vvwt.tm.infrastructure.web.dto.TeamUpdateRequest} but lives in the Modulith target package
 * {@code de.vvwt.tm.tournament.internal.dto}.
 *
 * <p>Inventory line 445 ({@code TeamUpdateRequest}).
 *
 * @see de.vvwt.tm.tournament.internal.TeamService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 445)</a>
 */
public record TeamUpdateRequest(

        /** New team name or label (null = no change). */
        String description,

        /** New team number (null = no change). */
        Integer teamNumber,

        /** New participate flag (null = no change). */
        Boolean participate,

        /** New refereeAssignment flag (null = no change). */
        Boolean refereeAssignment,

        /** New withoutAssessment flag (null = no change). */
        Boolean withoutAssessment) {

    /**
     * Resolves {@code teamNumber} for service consumption. Returns 0 when null (signals "no change"
     * to the service).
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
