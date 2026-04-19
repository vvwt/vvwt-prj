package de.vvwt.tm.infrastructure.tournament.dto;

import de.vvwt.tm.domain.ActivityType;
import java.util.UUID;

/**
 * Response body for GET/POST/PUT {@code /api/tournaments/{tournamentId}/activity-types} (E20S02,
 * AC2).
 *
 * <p>Reconstructed TDD-first under Approach C (E20 methodology). Behaviour reference: {@code
 * archive/E08S06-pre-dec28}.
 *
 * @see de.vvwt.tm.infrastructure.tournament.ActivityTypeController
 */
public record ActivityTypeResponse(
        UUID id,
        UUID tournamentId,
        String name,
        String assignmentRule,
        Integer capacityPerRound,
        int sortOrder) {

    /**
     * Factory method — maps an {@link ActivityType} entity to this response record.
     *
     * @param at the entity (NOT NULL)
     * @return the response record
     */
    public static ActivityTypeResponse from(ActivityType at) {
        return new ActivityTypeResponse(
                at.getId(),
                at.getTournamentId(),
                at.getName(),
                at.getAssignmentRule(),
                at.getCapacityPerRound(),
                at.getSortOrder());
    }
}
