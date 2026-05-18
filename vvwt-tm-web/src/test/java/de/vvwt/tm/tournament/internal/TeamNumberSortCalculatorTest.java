// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TeamNumberSortCalculator} (E66S01 AC4, DEC-77 D-2, DEC-22).
 *
 * <p>Updated from E58S03: uses the new {@code rank(...)} interface returning flat ranked list.
 * DEC-77 D-2: team_number ranks by registration number ascending.
 *
 * @see TeamNumberSortCalculator
 * @see TeamSortCalculator
 * @see <a href="DEC-77">DEC-77 D-2 — team_number: ascending by registration number</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E66S01">E66S01 — AC4</a>
 */
@DisplayName("TeamNumberSortCalculator unit tests — E66S01 AC4")
class TeamNumberSortCalculatorTest {

    private final TeamSortCalculator calculator = new TeamNumberSortCalculator();

    @Test
    @DisplayName("getKeyId() returns 'team_number'")
    void getKeyId_returnsTeamNumber() {
        assertThat(calculator.getKeyId()).isEqualTo("team_number");
    }

    // -------------------------------------------------------------------------
    // AC4: flat list sorted by teamNumber ASC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rank() returns flat list sorted by teamNumber ASC")
    void rank_sortsByTeamNumberAscending() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID(), t3 = UUID.randomUUID();
        TeamAvatar av1 = avatar(1, 1, t1);
        TeamAvatar av2 = avatar(1, 2, t2);
        TeamAvatar av3 = avatar(2, 1, t3);

        // t1=num3, t2=num1, t3=num2
        Map<UUID, Team> teamById = Map.of(
                t1, team(t1, 3, "Team3"),
                t2, team(t2, 1, "Team1"),
                t3, team(t3, 2, "Team2"));

        List<RankedTeamEntry> ranked = calculator.rank(List.of(av1, av2, av3), Map.of(), teamById);

        assertThat(ranked).hasSize(3);
        // Expected: Team1(t2), Team2(t3), Team3(t1)
        assertThat(ranked.get(0).teamNumber()).isEqualTo(1);
        assertThat(ranked.get(0).teamId()).isEqualTo(t2);
        assertThat(ranked.get(1).teamNumber()).isEqualTo(2);
        assertThat(ranked.get(1).teamId()).isEqualTo(t3);
        assertThat(ranked.get(2).teamNumber()).isEqualTo(3);
        assertThat(ranked.get(2).teamId()).isEqualTo(t1);
    }

    @Test
    @DisplayName("rank() with empty fromAvatars returns empty list")
    void rank_emptyAvatars_returnsEmpty() {
        assertThat(calculator.rank(List.of(), Map.of(), Map.of())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC7: teamId carried from avatar (not written to DB)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rank() carries fromAvatar.teamId (not written to DB — AC7)")
    void rank_carriesTeamIdFromAvatar() {
        UUID t1 = UUID.randomUUID();
        TeamAvatar av1 = avatar(1, 1, t1);
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 1, "T1"));

        List<RankedTeamEntry> ranked = calculator.rank(List.of(av1), Map.of(), teamById);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).teamId()).isEqualTo(t1);
    }

    // -------------------------------------------------------------------------
    // AC2: entries carry source coordinates, not distribution slots
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rank() entries carry source structural coordinates from predecessor phase")
    void rank_entriesCarrySourceCoordinates() {
        UUID t1 = UUID.randomUUID();
        TeamAvatar av1 = avatar(2, 3, t1);
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 7, "T1"));

        List<RankedTeamEntry> ranked = calculator.rank(List.of(av1), Map.of(), teamById);

        assertThat(ranked.get(0).sourceGroupNumber()).isEqualTo(2);
        assertThat(ranked.get(0).sourceGroupPosition()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatar(int groupNumber, int groupPosition, UUID teamId) {
        TeamAvatar av = new TeamAvatar();
        av.setId(UUID.randomUUID());
        av.setGroupNumber(groupNumber);
        av.setGroupPosition(groupPosition);
        av.setTeamId(teamId);
        return av;
    }

    private static Team team(UUID id, int number, String description) {
        Team t = new Team();
        t.setId(id);
        t.setTeamNumber(number);
        t.setDescription(description);
        return t;
    }
}
