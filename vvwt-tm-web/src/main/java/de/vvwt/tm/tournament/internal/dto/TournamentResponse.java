package de.vvwt.tm.tournament.internal.dto;

import de.vvwt.tm.tournament.Tournament;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * REST response DTO for a single tournament — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.dto.TournamentResponse} but lives in the Modulith
 * target package {@code de.vvwt.tm.tournament.internal.dto}. Uses the new {@link Tournament} entity
 * from {@code de.vvwt.tm.tournament}.
 *
 * <p>The internal {@code tenantId} is intentionally excluded — it is an implementation detail, not
 * a client-visible field.
 *
 * @see Tournament
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
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
        LocalDateTime createdAt,
        LocalTime plannedStartTime) {

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
                t.getCreatedAt(),
                t.getPlannedStartTime());
    }
}
