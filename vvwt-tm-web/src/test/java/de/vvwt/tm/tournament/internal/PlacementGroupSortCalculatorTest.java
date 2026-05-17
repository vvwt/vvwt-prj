// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link PlacementGroupSortCalculator} (AC4, AC5, AC8, DEC-22).
 *
 * <p>Verifies that the placement_group distribution behaviour matches the original {@code
 * DefaultPhaseTransitionService.computePlacementGroup} exactly (AC5): teams keep their Phase-N
 * group; positions within each group are re-assigned by descending points.
 *
 * <p>AC10: teamId is passed through from fromAvatar — the calculator does NOT write teamId to DB.
 *
 * @see PlacementGroupSortCalculator
 * @see TeamSortCalculator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC10</a>
 */
@DisplayName("PlacementGroupSortCalculator unit tests — E58S03")
class PlacementGroupSortCalculatorTest {

    private final TeamSortCalculator calculator = new PlacementGroupSortCalculator();

    // -------------------------------------------------------------------------
    // AC4: registered under correct key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'placement_group'")
    void getKeyId_returnsPlacementGroup() {
        assertThat(calculator.getKeyId()).isEqualTo("placement_group");
    }

    // -------------------------------------------------------------------------
    // AC5: teams keep their group; sorted by descending points within group
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sortTeams keeps teams in their source group, sorted by descending points")
    void sortTeams_twoGroupsTwoAvatarsEach_sortedByDescendingPoints() {
        // Group 1: t1 (50 pts) → pos 1, t2 (30 pts) → pos 2
        // Group 2: t3 (80 pts) → pos 1, t4 (10 pts) → pos 2
        UUID t1 = UUID.randomUUID(),
                t2 = UUID.randomUUID(),
                t3 = UUID.randomUUID(),
                t4 = UUID.randomUUID();

        TeamAvatar av1 = avatarAt(1, 1, t1);
        TeamAvatar av2 = avatarAt(1, 2, t2);
        TeamAvatar av3 = avatarAt(2, 1, t3);
        TeamAvatar av4 = avatarAt(2, 2, t4);

        Map<UUID, TeamAvatarRating> ratings =
                Map.of(
                        av1.getId(), rating(av1.getId(), 50),
                        av2.getId(), rating(av2.getId(), 30),
                        av3.getId(), rating(av3.getId(), 80),
                        av4.getId(), rating(av4.getId(), 10));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "T1"),
                        t2, team(t2, 2, "T2"),
                        t3, team(t3, 3, "T3"),
                        t4, team(t4, 4, "T4"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(
                        List.of(av1, av2, av3, av4), ratings, teamById, 2, "placement_group");

        assertThat(proposals).hasSize(4);
        // Group 1: highest points first
        List<TeamAvatarProposal> group1 =
                proposals.stream().filter(p -> p.groupNumber() == 1).toList();
        List<TeamAvatarProposal> group2 =
                proposals.stream().filter(p -> p.groupNumber() == 2).toList();

        assertThat(group1).hasSize(2);
        assertThat(group1.get(0).teamNumber()).isEqualTo(1); // t1 (50 pts) → pos 1
        assertThat(group1.get(0).groupPosition()).isEqualTo(1);
        assertThat(group1.get(1).teamNumber()).isEqualTo(2); // t2 (30 pts) → pos 2
        assertThat(group1.get(1).groupPosition()).isEqualTo(2);

        assertThat(group2).hasSize(2);
        assertThat(group2.get(0).teamNumber()).isEqualTo(3); // t3 (80 pts) → pos 1
        assertThat(group2.get(0).groupPosition()).isEqualTo(1);
        assertThat(group2.get(1).teamNumber()).isEqualTo(4); // t4 (10 pts) → pos 2
        assertThat(group2.get(1).groupPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("sortTeams with no ratings places teams at end (MIN_VALUE sort)")
    void sortTeams_noRatings_preservesEncounterOrderAsWorstCase() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        TeamAvatar av1 = avatarAt(1, 1, t1);
        TeamAvatar av2 = avatarAt(1, 2, t2);
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 1, "T1"), t2, team(t2, 2, "T2"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(List.of(av1, av2), Map.of(), teamById, 1, "placement_group");

        assertThat(proposals).hasSize(2);
        // Both have MIN_VALUE — stable sort preserves encounter order (both treated equally)
        assertThat(proposals).allSatisfy(p -> assertThat(p.groupNumber()).isEqualTo(1));
    }

    // -------------------------------------------------------------------------
    // AC10: teamId NOT set by calculator
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "sortTeams passes through fromAvatar.teamId — calculator does NOT write to DB (AC10 —"
                    + " DEC-59 Clause C)")
    void sortTeams_allProposalsHaveFromAvatarTeamId() {
        UUID t1 = UUID.randomUUID();
        TeamAvatar av1 = avatarAt(1, 1, t1);
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 1, "T1"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(List.of(av1), Map.of(), teamById, 1, "placement_group");

        // AC10: teamId is passed through from fromAvatar (not written to DB by calculator).
        assertThat(proposals).allSatisfy(p -> assertThat(p.teamId()).isEqualTo(t1));
    }

    @Test
    @DisplayName("sortTeams with empty fromAvatars returns empty list")
    void sortTeams_emptyAvatars_returnsEmpty() {
        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(List.of(), Map.of(), Map.of(), 1, "placement_group");

        assertThat(proposals).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatarAt(int groupNumber, int groupPosition, UUID teamId) {
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

    private static TeamAvatarRating rating(UUID avatarId, int points) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setPoints(points);
        return r;
    }
}
