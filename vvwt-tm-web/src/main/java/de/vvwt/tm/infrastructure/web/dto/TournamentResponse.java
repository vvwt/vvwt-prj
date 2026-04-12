package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.Tournament;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response DTO for a single tournament (AC1, AC2, AC3 — E05S04).
 *
 * <p>Exposes only the fields required by the SPA (description, appointment, status,
 * matchFormat, fieldCount, teamCount, and all strategy bean IDs). The internal
 * {@code tenantId} is intentionally excluded from the response — it is an implementation
 * detail, not a client-visible field.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
public record TournamentResponse(
        UUID id,
        String description,
        LocalDateTime appointment,
        String status,
        String matchFormat,
        int fieldCount,
        int teamCount,
        String scoringRuleId,
        String setValidationRuleId,
        String matchGeneratorId,
        LocalDateTime createdAt
) {

    /**
     * Maps a {@link Tournament} domain entity to a {@link TournamentResponse} DTO.
     *
     * @param t the tournament entity (must not be {@code null})
     * @return the corresponding response DTO
     */
    public static TournamentResponse from(Tournament t) {
        return new TournamentResponse(
                t.getId(),
                t.getDescription(),
                t.getAppointment(),
                t.getStatus(),
                t.getMatchFormat(),
                t.getFieldCount(),
                t.getTeamCount(),
                t.getScoringRuleId(),
                t.getSetValidationRuleId(),
                t.getMatchGeneratorId(),
                t.getCreatedAt()
        );
    }
}
