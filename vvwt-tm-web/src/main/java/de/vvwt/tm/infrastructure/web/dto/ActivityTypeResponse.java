package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.ActivityType;

import java.util.UUID;

/**
 * Response body for GET/POST/PUT /api/tournaments/{tournamentId}/activity-types (E08S06, AC1).
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
public record ActivityTypeResponse(
        UUID id,
        UUID tournamentId,
        String name,
        String assignmentRule,
        Integer capacityPerRound,
        int sortOrder
) {
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
                at.getSortOrder()
        );
    }
}
