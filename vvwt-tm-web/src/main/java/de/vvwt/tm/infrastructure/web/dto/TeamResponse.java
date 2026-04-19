package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.Team;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response DTO for a single team (AC1, AC2, AC3 — E05S05).
 *
 * <p>Exposes only the client-visible fields. The {@code tenantId} is intentionally excluded — it is
 * an implementation detail, not a client-visible field (DEC-17).
 *
 * <p>The {@code hasPhoto} field (E12S02 AC4) indicates whether a team photo has been uploaded for
 * this team in its tournament. Populated by the listing endpoint; defaults to {@code false} for
 * responses that do not require photo presence information.
 *
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story
 *     E05S05</a>
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
public record TeamResponse(
        UUID id,
        UUID tournamentId,
        int teamNumber,
        String description,
        boolean participate,
        boolean refereeAssignment,
        boolean withoutAssessment,
        LocalDateTime createdAt,
        boolean hasPhoto) {

    /**
     * Maps a {@link Team} domain entity to a {@link TeamResponse} DTO with {@code hasPhoto =
     * false}.
     *
     * <p>Use this factory for contexts where photo presence is not relevant (e.g. create/update
     * responses). For listing responses, use {@link #from(Team, boolean)}.
     *
     * @param t the team entity (must not be {@code null})
     * @return the corresponding response DTO with {@code hasPhoto = false}
     */
    public static TeamResponse from(Team t) {
        return from(t, false);
    }

    /**
     * Maps a {@link Team} domain entity to a {@link TeamResponse} DTO with the given {@code
     * hasPhoto} value (E12S02 AC4).
     *
     * @param t the team entity (must not be {@code null})
     * @param hasPhoto whether a photo exists for this team in its tournament
     * @return the corresponding response DTO
     */
    public static TeamResponse from(Team t, boolean hasPhoto) {
        return new TeamResponse(
                t.getId(),
                t.getTournamentId(),
                t.getTeamNumber(),
                t.getDescription(),
                t.isParticipate(),
                t.isRefereeAssignment(),
                t.isWithoutAssessment(),
                t.getCreatedAt(),
                hasPhoto);
    }
}
