package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.Team;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response DTO for a single team (AC1, AC2, AC3 — E05S05).
 *
 * <p>Exposes only the client-visible fields. The {@code tenantId} is intentionally excluded —
 * it is an implementation detail, not a client-visible field (DEC-17).
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story E05S05</a>
 */
public record TeamResponse(
        UUID id,
        UUID tournamentId,
        int teamNumber,
        String description,
        boolean participate,
        boolean refereeAssignment,
        boolean withoutAssessment,
        LocalDateTime createdAt
) {

    /**
     * Maps a {@link Team} domain entity to a {@link TeamResponse} DTO.
     *
     * @param t the team entity (must not be {@code null})
     * @return the corresponding response DTO
     */
    public static TeamResponse from(Team t) {
        return new TeamResponse(
                t.getId(),
                t.getTournamentId(),
                t.getTeamNumber(),
                t.getDescription(),
                t.isParticipate(),
                t.isRefereeAssignment(),
                t.isWithoutAssessment(),
                t.getCreatedAt()
        );
    }
}
