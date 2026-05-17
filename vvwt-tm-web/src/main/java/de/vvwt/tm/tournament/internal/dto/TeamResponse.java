// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import de.vvwt.tm.tournament.Team;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REST response DTO for a single team (E21S04, AC-TDD-TeamDTOs).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.dto.TeamResponse} but lives in the Modulith
 * target package {@code de.vvwt.tm.tournament.internal.dto}. Wraps the new {@link Team} entity from
 * {@code de.vvwt.tm.tournament} — does NOT import the legacy {@code de.vvwt.tm.domain.Team}.
 *
 * <p>The {@code hasPhoto} field indicates whether a team photo has been uploaded. Defaults to
 * {@code false} for non-listing responses.
 *
 * <p>Inventory line 444 ({@code TeamResponse}).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTeamService
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 444)</a>
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
     * Maps a {@link Team} entity to a {@link TeamResponse} DTO with {@code hasPhoto = false}.
     *
     * @param t the team entity (must not be {@code null})
     * @return the corresponding response DTO with {@code hasPhoto = false}
     */
    public static TeamResponse from(Team t) {
        return from(t, false);
    }

    /**
     * Maps a {@link Team} entity to a {@link TeamResponse} DTO with the given {@code hasPhoto}
     * value.
     *
     * @param t the team entity (must not be {@code null})
     * @param hasPhoto whether a photo exists for this team
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
