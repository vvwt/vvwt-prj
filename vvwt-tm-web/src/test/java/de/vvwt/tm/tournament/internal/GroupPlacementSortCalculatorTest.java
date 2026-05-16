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
 * RED-first unit tests for {@link GroupPlacementSortCalculator} (AC4, AC5, AC8, DEC-22).
 *
 * <p>Verifies that the group_placement distribution behaviour matches the original {@code
 * DefaultPhaseTransitionService.computeGroupPlacement} exactly (AC5): rank-N finisher from every
 * Phase-N group → target group N; truncates to minimum group size.
 *
 * <p>AC10: teamId is passed through from fromAvatar — the calculator does NOT write teamId to DB.
 *
 * @see GroupPlacementSortCalculator
 * @see TeamSortCalculator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC10</a>
 */
@DisplayName("GroupPlacementSortCalculator unit tests — E58S03")
class GroupPlacementSortCalculatorTest {

    private final TeamSortCalculator calculator = new GroupPlacementSortCalculator();

    // -------------------------------------------------------------------------
    // AC4: registered under correct key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'group_placement'")
    void getKeyId_returnsGroupPlacement() {
        assertThat(calculator.getKeyId()).isEqualTo("group_placement");
    }

    // -------------------------------------------------------------------------
    // AC5: rank-N from each group → target group N; truncates to min group size
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sortTeams crosses groups: rank-1 from each → group 1, rank-2 → group 2")
    void sortTeams_twoGroupsTwoAvatarsEach_crossGroupPlacement() {
        // Group 1: t1 (80 pts rank-1), t2 (30 pts rank-2)
        // Group 2: t3 (70 pts rank-1), t4 (10 pts rank-2)
        // Expected: group 1 gets t1 + t3, group 2 gets t2 + t4
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
                        av1.getId(), rating(av1.getId(), 80),
                        av2.getId(), rating(av2.getId(), 30),
                        av3.getId(), rating(av3.getId(), 70),
                        av4.getId(), rating(av4.getId(), 10));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "T1"),
                        t2, team(t2, 2, "T2"),
                        t3, team(t3, 3, "T3"),
                        t4, team(t4, 4, "T4"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(
                        List.of(av1, av2, av3, av4), ratings, teamById, 2, "group_placement");

        assertThat(proposals).hasSize(4);

        List<TeamAvatarProposal> targetGroup1 =
                proposals.stream().filter(p -> p.groupNumber() == 1).toList();
        List<TeamAvatarProposal> targetGroup2 =
                proposals.stream().filter(p -> p.groupNumber() == 2).toList();

        // Rank-1 from group 1 and group 2 → target group 1
        assertThat(targetGroup1).hasSize(2);
        assertThat(targetGroup1)
                .extracting(TeamAvatarProposal::teamNumber)
                .containsExactlyInAnyOrder(1, 3); // t1 and t3

        // Rank-2 from group 1 and group 2 → target group 2
        assertThat(targetGroup2).hasSize(2);
        assertThat(targetGroup2)
                .extracting(TeamAvatarProposal::teamNumber)
                .containsExactlyInAnyOrder(2, 4); // t2 and t4
    }

    @Test
    @DisplayName("sortTeams truncates to minimum group size when groups are unequal")
    void sortTeams_unequalGroups_truncatesToMinSize() {
        // Group 1: 3 avatars; Group 2: 2 avatars → truncate to 2 per rank
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID(), t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID(), t5 = UUID.randomUUID();

        TeamAvatar av1 = avatarAt(1, 1, t1); // group 1
        TeamAvatar av2 = avatarAt(1, 2, t2);
        TeamAvatar av3 = avatarAt(1, 3, t3);
        TeamAvatar av4 = avatarAt(2, 1, t4); // group 2
        TeamAvatar av5 = avatarAt(2, 2, t5);

        Map<UUID, TeamAvatarRating> ratings =
                Map.of(
                        av1.getId(), rating(av1.getId(), 90),
                        av2.getId(), rating(av2.getId(), 50),
                        av3.getId(), rating(av3.getId(), 10),
                        av4.getId(), rating(av4.getId(), 80),
                        av5.getId(), rating(av5.getId(), 20));
        Map<UUID, Team> teamById =
                Map.of(
                        t1,
                        team(t1, 1, "T1"),
                        t2,
                        team(t2, 2, "T2"),
                        t3,
                        team(t3, 3, "T3"),
                        t4,
                        team(t4, 4, "T4"),
                        t5,
                        team(t5, 5, "T5"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(
                        List.of(av1, av2, av3, av4, av5), ratings, teamById, 2, "group_placement");

        // minSize = 2 → only 2 rank slots → 4 proposals total (not 5)
        assertThat(proposals).hasSize(4);
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
                calculator.sortTeams(List.of(av1), Map.of(), teamById, 1, "group_placement");

        // AC10: teamId is passed through from fromAvatar (not written to DB by calculator).
        assertThat(proposals).allSatisfy(p -> assertThat(p.teamId()).isEqualTo(t1));
    }

    @Test
    @DisplayName("sortTeams with empty fromAvatars returns empty list")
    void sortTeams_emptyAvatars_returnsEmpty() {
        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(List.of(), Map.of(), Map.of(), 1, "group_placement");

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
