package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.RefereeAssignmentService.RefereeAssignmentEntry;

import java.util.UUID;

/**
 * REST representation of a single match referee assignment (E05S09 AC1, AC2, AC3).
 *
 * @param matchId              the match UUID
 * @param lapNumber            the lap number (null if slots not yet assigned)
 * @param fieldNumber          the field number (null if slots not yet assigned)
 * @param team1Description     display name for team 1
 * @param team2Description     display name for team 2
 * @param refereeTeamName      display name for the referee team (null if unassigned)
 * @param refereeTeamId        UUID of the referee team (null if unassigned)
 * @param refereeTeamAvatarId  avatar ID of the referee team in this phase — used when
 *                             submitting an override request (null if unassigned)
 * @param isManualOverride     true if this assignment was set manually by the organizer
 */
public record RefereeAssignmentEntryResponse(
        UUID matchId,
        Integer lapNumber,
        Integer fieldNumber,
        String team1Description,
        String team2Description,
        String refereeTeamName,
        UUID refereeTeamId,
        UUID refereeTeamAvatarId,
        boolean isManualOverride
) {
    /**
     * Converts a domain assignment entry to a REST response.
     *
     * @param entry the domain entry (must not be {@code null})
     * @return the REST response
     */
    public static RefereeAssignmentEntryResponse from(RefereeAssignmentEntry entry) {
        return new RefereeAssignmentEntryResponse(
                entry.matchId(),
                entry.lapNumber(),
                entry.fieldNumber(),
                entry.team1Description(),
                entry.team2Description(),
                entry.refereeTeamName(),
                entry.refereeTeamId(),
                entry.refereeTeamAvatarId(),
                entry.isManualOverride()
        );
    }
}
