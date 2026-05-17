// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TeamAvatar} entity invariants (E21S04, AC-TDD-TeamAvatar).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TeamAvatar} at {@code de.vvwt.tm.tournament.TeamAvatar}
 * did not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Structural identity fields (DEC-9) are accessible and settable
 *   <li>Entities with same UUID have the same id
 *   <li>All fields settable/gettable via accessors
 * </ul>
 *
 * @see TeamAvatar
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity via (phaseId, groupNumber,
 *     groupPosition)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 455)</a>
 */
@DisplayName("TeamAvatar entity invariants — E21S04 AC-TDD-TeamAvatar")
class TeamAvatarTest {

    @Test
    @DisplayName("entities with same UUID have the same id")
    void entitiesWithSameUuidHaveSameId() {
        UUID id = UUID.randomUUID();
        TeamAvatar a = new TeamAvatar();
        TeamAvatar b = new TeamAvatar();
        a.setId(id);
        b.setId(id);
        assertThat(a.getId()).isEqualTo(b.getId());
    }

    @Test
    @DisplayName("structural identity fields (DEC-9) are accessible")
    void structuralIdentityFieldsAccessible() {
        UUID phaseId = UUID.randomUUID();
        TeamAvatar avatar = new TeamAvatar();
        avatar.setPhaseId(phaseId);
        avatar.setGroupNumber(2);
        avatar.setGroupPosition(3);

        assertThat(avatar.getPhaseId()).isEqualTo(phaseId);
        assertThat(avatar.getGroupNumber()).isEqualTo(2);
        assertThat(avatar.getGroupPosition()).isEqualTo(3);
    }

    @Test
    @DisplayName("all fields settable and gettable via accessors")
    void allFieldsSettableAndGettable() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        TeamAvatar avatar = new TeamAvatar();
        avatar.setId(id);
        avatar.setTournamentId(tournamentId);
        avatar.setPhaseId(phaseId);
        avatar.setGroupNumber(1);
        avatar.setGroupPosition(4);
        avatar.setTeamId(teamId);
        avatar.setDescription("Group A, Position 4");
        avatar.setCreatedAt(now);

        assertThat(avatar.getId()).isEqualTo(id);
        assertThat(avatar.getTournamentId()).isEqualTo(tournamentId);
        assertThat(avatar.getPhaseId()).isEqualTo(phaseId);
        assertThat(avatar.getGroupNumber()).isEqualTo(1);
        assertThat(avatar.getGroupPosition()).isEqualTo(4);
        assertThat(avatar.getTeamId()).isEqualTo(teamId);
        assertThat(avatar.getDescription()).isEqualTo("Group A, Position 4");
        assertThat(avatar.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("null description is accepted (optional field)")
    void nullDescriptionAccepted() {
        TeamAvatar avatar = new TeamAvatar();
        avatar.setDescription(null);
        assertThat(avatar.getDescription()).isNull();
    }
}
